// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Derived from:
// com.google.devtools.build.buildjar.VanillaJavaBuilder and
// com.google.devtools.build.buildjar.javac.JavacOptions.java

package com.salesforce.bazel.jdt.toolchain.builder;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.io.MoreFiles;
import com.google.devtools.build.buildjar.proto.JavaCompilation;
import com.google.devtools.build.lib.view.proto.Deps;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import com.salesforce.bazel.jdt.toolchain.builder.jarhelper.JarCreator;

public class JdtJavaBuilder implements Closeable {

    /** Warning text when the output from the JDT compile is too long. */
    private static final String CONTENT_TOO_LONG_WARNING = "\nWARNING: Output from JdtJavaBuilder was too long - truncated\n";
    // Default value comes from com.google.devtools.build.lib.runtime.UiEventHandler::getContentIfSmallEnough
    private static final int DEFAULT_MAX_STD_OUT_ERR_BYTES = 1_048_576;
    /** Cache of opened zip filesystems. */
    private final Map<Path, FileSystem> filesystems = new HashMap<>();

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--persistent_worker")) {
            System.exit(runPersistentWorker());
        } else {
            try (JdtJavaBuilder builder = new JdtJavaBuilder()) {
                JdtJavaBuilderResult result = builder.run(ImmutableList.copyOf(args));
                System.err.print(result.output());
                System.exit(result.ok() ? 0 : 1);
            }
        }
    }

    private static int runPersistentWorker() {
        while (true) {
            try {
                WorkRequest request = WorkRequest.parseDelimitedFrom(System.in);
                if (request == null) {
                    break;
                }
                JdtJavaBuilderResult result;
                try (JdtJavaBuilder builder = new JdtJavaBuilder()) {
                    result = builder.run(request.getArgumentsList());
                }
                /*
                 * As soon as we write the response, bazel will start cleaning
                 * up the working tree. The JdtJavaBuilder must be fully
                 * closed at this point.
                 */
                WorkResponse response = WorkResponse.newBuilder()
                                                    .setOutput(result.output())
                                                    .setExitCode(result.ok() ? 0 : 1)
                                                    .setRequestId(request.getRequestId())
                                                    .build();
                response.writeDelimitedTo(System.out);
                System.out.flush();
            } catch (IOException e) {
                e.printStackTrace();
                return 1;
            }
        }
        return 0;
    }

    /**
     * Derive a temporary directory path based on the path to the output jar
     */
    private static Path deriveOutputDirectory(String label, String outputJar) throws IOException {
        checkArgument(label != null, "--target_label is required");
        checkArgument(outputJar != null, "--output is required");
        checkArgument(label.contains(":"), "--target_label must be a canonical label (containing a `:`)");

        Path path = Paths.get(outputJar);
        String base = label.substring(label.lastIndexOf(':') + 1);
        return path.resolveSibling("_jdt").resolve(base);
    }

    /**
     * Creates and cleans the output directories.
     */
    private static void initializeLocations(SimpleOptionsParser optionsParser, Path nativeHeaderDir, Path sourceGenDir, Path classDir,
            Path sourceJarDir) throws IOException {
        createOutputDirectory(sourceGenDir);
        if (optionsParser.getNativeHeaderOutput() != null) {
            createOutputDirectory(nativeHeaderDir);
        }
        createOutputDirectory(classDir);
        createOutputDirectory(sourceJarDir);
    }

    private static void createOutputDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walk(dir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
        Files.createDirectories(dir);
    }

    /**
     * Writes a jar containing any sources generated by annotation processors.
     */
    private static void writeGeneratedSourceOutput(Path sourceGenDir, SimpleOptionsParser optionsParser) throws IOException {
        if (optionsParser.getGeneratedSourcesOutputJar() == null) {
            return;
        }
        JarCreator jar = new JarCreator(Path.of(optionsParser.getGeneratedSourcesOutputJar()));
        jar.setNormalize(true);
        jar.setCompression(optionsParser.isCompressJar());
        jar.addDirectory(sourceGenDir);
        jar.execute();
    }

    private static void writeNativeHeaderOutput(SimpleOptionsParser optionsParser, Path nativeHeaderDir) throws IOException {
        if (optionsParser.getNativeHeaderOutput() == null) {
            return;
        }
        JarCreator jar = new JarCreator(Path.of(optionsParser.getNativeHeaderOutput()));
        try {
            jar.setNormalize(true);
            jar.setCompression(optionsParser.isCompressJar());
            jar.addDirectory(nativeHeaderDir);
        } finally {
            jar.execute();
        }
    }

    /**
     * Writes the class output jar, including any resource entries.
     */
    private static void writeOutput(Path classDir, SimpleOptionsParser optionsParser) throws IOException {
        JarCreator jar = new JarCreator(Path.of(optionsParser.getOutputJar()));
        jar.setNormalize(true);
        jar.setCompression(optionsParser.isCompressJar());
        jar.addDirectory(classDir);
        jar.execute();
    }

    /**
     * Returns an immutable list containing all the non-Bazel specific Javac flags.
     * Filters a list of javac flags excluding Bazel-specific flags.
     * Also defaults java source and target versions to 11 as JDT defaults it to 1.5, if not specified.
     */
    public static ImmutableList<String> removeBazelSpecificFlags(Iterable<String> javacopts) {
        ImmutableList.Builder<String> standardJavacopts = ImmutableList.builder();
        boolean hasJavaSourceOption = false;
        boolean hasJavaTargetOption = false;
        boolean hasJavaReleaseOption = false;

        for (String opt : javacopts) {
            if (!isBazelSpecificFlag(opt)) {
                if (opt.equalsIgnoreCase("-source")) {
                    hasJavaSourceOption = true;
                } else if (opt.equalsIgnoreCase("-target")) {
                    hasJavaTargetOption = true;
                } else if (opt.equalsIgnoreCase("--release")) {
                    hasJavaReleaseOption = true;
                }
                standardJavacopts.add(opt);
            }
        }

        if (!hasJavaReleaseOption) {
            if (!hasJavaTargetOption) {
                standardJavacopts.add("-target");
                standardJavacopts.add("11");
                if (!hasJavaSourceOption) {
                    standardJavacopts.add("-source");
                    standardJavacopts.add("11");
                }
            }
        }
        return standardJavacopts.build();
    }

    private static boolean isBazelSpecificFlag(String opt) {
        return opt.startsWith("-Werror:") || opt.startsWith("-Xep");
    }

    private FileSystem getJarFileSystem(Path sourceJar) throws IOException {
        FileSystem fs = filesystems.get(sourceJar);
        if (fs == null) {
            filesystems.put(sourceJar, fs = FileSystems.newFileSystem(sourceJar, null));
        }
        return fs;
    }

    @Override
    public void close() throws IOException {}

    public JdtJavaBuilderResult run(List<String> args) throws IOException {
        SimpleOptionsParser optionsParser;
        try {
            optionsParser = new SimpleOptionsParser(args);
        } catch (InvalidCommandLineException e) {
            return new JdtJavaBuilderResult(false, e.getMessage());
        }

        boolean ok;
        int maxStdOutErrBytes = optionsParser.getMaxStdOutErr().orElse(DEFAULT_MAX_STD_OUT_ERR_BYTES);

        Path jdtOutputDir = JdtJavaBuilder.deriveOutputDirectory(optionsParser.getTargetLabel(), optionsParser.getOutputJar());
        Path classDir = jdtOutputDir.resolve(Path.of("classes"));
        Path nativeHeaderDir = jdtOutputDir.resolve(Path.of("native_headers"));
        Path sourceGenDir = jdtOutputDir.resolve("sources");
        Path sourceJarDir = jdtOutputDir.resolve("source_jars");

        initializeLocations(optionsParser, nativeHeaderDir, sourceGenDir, classDir, sourceJarDir);
        ImmutableList<String> sources = getSources(optionsParser, sourceJarDir);

        StringWriter sysOutWriter = new StringWriter();
        StringWriter sysErrWriter = new StringWriter();
        StringBuilder jdtHeader = new StringBuilder();
        if (optionsParser.getJdtDebug().orElse(false)) {
            jdtHeader.append("><>< :: Using JdtJavaBuilder :: ><><\n\n");
        }

        if (sources.isEmpty()) {
            ok = true;
        } else {
            org.eclipse.jdt.core.compiler.CompilationProgress progress = null; // instantiate your subclass
            StringBuilder commandLineBuilder = new StringBuilder();

            // Use javac options from Bazel config, and remove Bazel specific flags.
            for (String javacOpt : removeBazelSpecificFlags(optionsParser.getJavacOpts())) {
                commandLineBuilder.append(javacOpt);
                commandLineBuilder.append(" ");
            }
            // Disable all warnings for now
            commandLineBuilder.append("-warn:none").append(" ");

            // TODO: This option lines up with ecj's -bootclasspath parameter, but is unnecessary for JDKs of version 9
            // or higher. Unfortunately, while the documentation indicates that the parameter is ignored, it seems that
            // in practice, an error is given if the parameter is passed when attempting to build source level 11.
            // commandLineBuilder.append("-bootclasspath ")
            // .append(String.join(File.pathSeparator, optionsParser.getBootClassPath()))
            // .append(" ");

            commandLineBuilder.append(String.join(" ", sources)).append(" ");
            commandLineBuilder.append("-d ").append(classDir).append(" ");
            commandLineBuilder.append("-s ").append(sourceGenDir).append(" ");
            if (!optionsParser.getProcessorNames().isEmpty()) {
                if(optionsParser.getJdtDebug().orElse(false)) {
                    commandLineBuilder.append("-XprintProcessorInfo ").append("-XprintRounds ");
                }
                commandLineBuilder.append("-processor ").append(String.join(",", optionsParser.getProcessorNames())).append(" ");
            }
            if (!optionsParser.getProcessorPath().isEmpty()) {
                commandLineBuilder.append("-processorpath ")
                                  .append(String.join(File.pathSeparator, optionsParser.getProcessorPath()))
                                  .append(" ");
                // if release/traget >= JDK 9 then -processorpath will be ignored by JDT but --processor-module-path is expected instead
                // (we set both to let JDT pick)
                commandLineBuilder.append("--processor-module-path ")
                                  .append(String.join(File.pathSeparator, optionsParser.getProcessorPath()))
                                  .append(" ");
            }
            if (optionsParser.getEclipsePreferencesFile().isPresent()) {
                File prefsFile = new File(optionsParser.getEclipsePreferencesFile().get());
                if (prefsFile.exists()) {
                    commandLineBuilder.append("-properties ");
                    commandLineBuilder.append(optionsParser.getEclipsePreferencesFile().get()).append(" ");
                }
            }
            commandLineBuilder.append("-Xemacs ");
            // Compile using only the direct dependencies, if requested to. Otherwise use the full set of direct
            // and indirect dependencies.
            if (optionsParser.getUseDirectDepsOnly().orElse(false) && !optionsParser.getDirectJars().isEmpty()) {
                commandLineBuilder.append("-classpath ").append(String.join(File.pathSeparator, optionsParser.getDirectJars())).append(" ");
            } else if (!optionsParser.getClassPath().isEmpty()) {
                commandLineBuilder.append("-classpath ").append(String.join(File.pathSeparator, optionsParser.getClassPath())).append(" ");
            }

            String commandLine = commandLineBuilder.toString();
            Files.writeString(jdtOutputDir.resolve("jdt.commandline"), commandLine.replace(' ', '\n'), StandardOpenOption.CREATE);

            ok = org.eclipse.jdt.core.compiler.batch.BatchCompiler.compile(commandLine,
                                                                           new PrintWriter(sysOutWriter),
                                                                           new PrintWriter(sysErrWriter),
                                                                           progress);
            if (sysOutWriter.getBuffer().length() > 0)
                sysOutWriter.append("\n");
            if (sysErrWriter.getBuffer().length() > 0)
                sysErrWriter.append("\n");

            if (optionsParser.getJdtDebug().orElse(false)) {
                jdtHeader.append("JDT command-line options: ");
                if (commandLine.length() > SimpleOptionsParser.getMaxJdtCommandLineDebug()) {
                    jdtHeader.append(commandLine.substring(0, SimpleOptionsParser.getMaxJdtCommandLineDebug())).append(" ...");
                } else {
                    jdtHeader.append(commandLine);
                }
            }
        }

        if (ok) {
            writeOutput(classDir, optionsParser);
            writeNativeHeaderOutput(optionsParser, nativeHeaderDir);
        }
        writeGeneratedSourceOutput(sourceGenDir, optionsParser);

        /*
         * The jdeps and manifest outputs don't include any information about dependencies, but Bazel still expects
         * the file to be created
         */
        if (optionsParser.getOutputDepsProtoFile() != null) {
            try (OutputStream os = Files.newOutputStream(Paths.get(optionsParser.getOutputDepsProtoFile()))) {
                Deps.Dependencies.newBuilder().setRuleLabel(optionsParser.getTargetLabel()).setSuccess(ok).build().writeTo(os);
            }
        }
        if (optionsParser.getManifestProtoPath() != null) {
            try (OutputStream os = Files.newOutputStream(Paths.get(optionsParser.getManifestProtoPath()))) {
                JavaCompilation.Manifest.getDefaultInstance().writeTo(os);
            }
        }

        return new JdtJavaBuilderResult(ok, trimOutputToSize(maxStdOutErrBytes, jdtHeader, sysOutWriter, sysErrWriter));
    }

    /**
     * The Bazel code will fail the build if the output from the compilation is too long (longer than the value
     * of --experimental_ui_max_stdouterr_bytes=6000000), so the output is trimmed to that size here. The compiler
     * output will quote sections of code that sometimes have unicode characters, so there are some additional
     * steps done here to measure the output in number of bytes.
     */
    private String trimOutputToSize(int maxStdOutErrBytes, StringBuilder jdtHeader, StringWriter sysOutWriter, StringWriter sysErrWriter) {
        // Note that space is reserved for the content warning to avoid backtracking using byte length
        int maxOutputSize = maxStdOutErrBytes - jdtHeader.toString().getBytes(StandardCharsets.UTF_8).length
                - CONTENT_TOO_LONG_WARNING.getBytes(StandardCharsets.UTF_8).length;
        StringBuffer sysErrBuffer = sysErrWriter.getBuffer();
        // System out from JDT seems to always be zero length, but keeping here in case.
        String sysOutBuffer = sysOutWriter.toString();
        int outputStringLenBytes = sysOutBuffer.getBytes(StandardCharsets.UTF_8).length;
        StringBuilder sb = new StringBuilder();

        int currentOutputSize = 0;
        for (int i = 0; i < sysErrBuffer.length(); i++) {
            int codePoint = sysErrBuffer.codePointAt(i);
            int byteCount = 0;
            if (0 <= codePoint && codePoint <= 0x7f)
                byteCount = 1;
            else if (0x80 <= codePoint && codePoint <= 0x7ff)
                byteCount = 2;
            else if (0x800 <= codePoint && codePoint <= 0xd7ff) // excluding the surrogate area
                byteCount = 3;
            else if (0xdc00 <= codePoint && codePoint <= 0xffff)
                byteCount = 3;
            else { // surrogate
                byteCount = 4;
            }

            if (currentOutputSize + byteCount < maxOutputSize) {
                currentOutputSize += byteCount;
                sb.appendCodePoint(codePoint);
            } else {
                sb.append(CONTENT_TOO_LONG_WARNING);
                break;
            }
        }

        // Only insert the standard output at the beginning if there is any, and it fits, otherwise
        // rely on standard error.
        if (outputStringLenBytes > 0 && (currentOutputSize + outputStringLenBytes < maxOutputSize)) {
            sb.insert(0, sysOutBuffer);
        }

        return sb.toString();
    }

    /**
     * Returns the sources to compile, including any source jar entries.
     */
    private ImmutableList<String> getSources(SimpleOptionsParser optionsParser, Path sourceJarDir) throws IOException {
        final ImmutableList.Builder<String> sourcesBuilder = ImmutableList.builder();
        sourcesBuilder.addAll(optionsParser.getSourceFiles());

        for (String sourceJar : optionsParser.getSourceJars()) {

            String jarName = MoreFiles.getNameWithoutExtension(Path.of(sourceJar));

            // Extract jars to <target_label_name>/source_jars/<source_jar_name>
            Path sourceJarOutputDir = sourceJarDir.resolve(jarName);
            MoreFiles.createParentDirectories(sourceJarOutputDir);
            for (final Path root : getJarFileSystem(Paths.get(sourceJar)).getRootDirectories()) {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {

                    @Override
                    public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                        if (path.getFileName().toString().endsWith(".java")) {
                            Path relativePath = root.relativize(path);
                            Path outputPath = sourceJarOutputDir.resolve(relativePath.toString());

                            if (!outputPath.getParent().toFile().exists()) {
                                MoreFiles.createParentDirectories(outputPath);
                            }
                            Files.copy(path, outputPath);

                            sourcesBuilder.add(outputPath.toString());
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        return sourcesBuilder.build();
    }

    /**
     * Return result of a {@link JdtJavaBuilderResult} build.
     */
    public static class JdtJavaBuilderResult {

        private final boolean ok;
        private final String output;

        public JdtJavaBuilderResult(boolean ok, String output) {
            this.ok = ok;
            this.output = output;
        }

        /**
         * True if the compilation was successful.
         */
        public boolean ok() {
            return ok;
        }

        /**
         * Log output from the compilation.
         */
        public String output() {
            return output;
        }
    }
}
