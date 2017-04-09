package backup2zip;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.commons.io.DirectoryWalker;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;

public class Backup2Zip {
    @Argument
    private final List<String> arguments = new ArrayList<String>();
    
    @Option(name = "--full")
    private boolean fullOption;
    
    @Option(name = "--incremental")
    private boolean incrementalOption;
    

    public static void main(String[] args) throws IOException, ZipException {
        new Backup2Zip().doMain(args);
    }

    public void doMain(String[] args) throws IOException, ZipException {
        final CmdLineParser parser = new CmdLineParser(this);
        
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            parser.printUsage(System.err);
            System.err.println();
            System.exit(1);
        }
        
        if (arguments.size() != 2) {
            parser.printUsage(System.err);
            System.exit(1);
        }
        
        if (!fullOption && !incrementalOption || fullOption && incrementalOption) {
            System.err.println("Must specify one of --full or --incremental");
        }
        final boolean doFull = fullOption;        
        
        final File sourceTree = new File(arguments.get(0));
        final File targetDir = new File(arguments.get(1));
        
        if (!sourceTree.exists() || !sourceTree.isDirectory()) {
            System.out.format("\"%s\" does not exist or is not a directory", sourceTree.getCanonicalPath());
        }
        if (!targetDir.exists() || !targetDir.isDirectory()) {
            System.out.format("\"%s\" does not exist or is not a directory", targetDir.getCanonicalPath());
        }
        
        final long now = Instant.now().toEpochMilli();
        final String zipName;
        long incrementalTime;
        if (doFull) {
            zipName = "backup.full." + now + ".zip";
            incrementalTime = 0;
        } else {
            zipName = "backup.incr." + now + ".zip";
            final int nameCount = targetDir.toPath().getNameCount(); 
            final File[] backupFiles = targetDir.listFiles(file ->  
                file.toPath().subpath(nameCount, nameCount + 1).toString().startsWith("backup."));
            if (backupFiles.length == 0) {
                incrementalTime = 0;
            } else {
                final List<String> backupFileNames = Arrays.asList(backupFiles).stream()
                        .map(file -> file.toPath().subpath(nameCount, nameCount + 1).toString())
                        .collect(Collectors.toList());
                final String backupFile = new TreeSet<String>(backupFileNames).descendingIterator().next();
                incrementalTime = Long.parseLong(backupFile.substring(12, backupFile.length() - 4));
            }
        }
        
        final ZipFile zipFile = new ZipFile(new File(targetDir, zipName));
        final ZipParameters parameters = new ZipParameters();
        parameters.setCompressionMethod(Zip4jConstants.COMP_DEFLATE);
        parameters.setCompressionLevel(Zip4jConstants.DEFLATE_LEVEL_NORMAL);

        
        final Walk walk = new Walk(sourceTree, zipFile, parameters, incrementalTime);
        walk.start();
        
        
    }    
    
    public class Walk extends DirectoryWalker<File> {

        private final ZipFile zipFile;
        private final ZipParameters parameters;
        private final File startDirectory;
        private final long incrementalTime;

        public Walk(File startDirectory, ZipFile zipFile, ZipParameters parameters, long incrementalTime) {
            this.zipFile = zipFile;
            this.parameters = parameters;
            this.startDirectory = startDirectory;
            this.incrementalTime = incrementalTime;
        }

        public void start() throws IOException {
            List<File> results = new ArrayList<>();
            walk(startDirectory, results);
        }

        @Override
        protected boolean handleDirectory(File directory, int depth, Collection<File> results) {
            return true;
        }

        @Override
        protected void handleFile(File file, int depth, Collection<File> results) {
            String zipDirectory = startDirectory.toPath().relativize(file.toPath()).toString();
            parameters.setRootFolderInZip(zipDirectory);

            try {
                BasicFileAttributes attr = Files.readAttributes(file.toPath(), BasicFileAttributes.class);

                if (attr.creationTime().toMillis() > incrementalTime || attr.lastModifiedTime().toMillis() > incrementalTime) {
                    zipFile.addFile(file, parameters);
                }
            } catch (ZipException | IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
