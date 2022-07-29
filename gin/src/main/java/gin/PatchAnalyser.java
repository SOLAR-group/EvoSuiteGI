package gin;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import com.sampullara.cli.Args;
import com.sampullara.cli.Argument;

import gin.util.MavenUtils;
import gin.util.Project;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.pmw.tinylog.Logger;

import gin.edit.Edit;
import gin.edit.Edit.EditType;
import gin.test.InternalTestRunner;
import gin.test.UnitTestResult;
import gin.test.UnitTestResultSet;

/**
 *   A handy utility for analysing patches. Not part of the main gin system.
 */
public class PatchAnalyser implements Serializable {

    private static final long serialVersionUID = -3749197264292832819L;

    private static final int REPS = 10;

    @Argument(alias = "f", description = "Required: Source filename", required = true)
    protected File source = null;

    @Argument(alias = "p", description = "Required: Patch description", required = true)
    protected String patchText = "|";

    @Argument(alias = "p", description = "Project name, required for maven and gradle projects")
    protected String projectName = null;

    @Argument(alias = "h", description = "Path to maven bin directory, e.g. /usr/local, required for maven projects")
    protected File mavenHome = null;

    @Argument(alias = "c", description = "Class name")
    protected String className;

    @Argument(alias = "cp", description = "Classpath")
    protected String classPath;

    @Argument(alias = "d", description = "Project directory")
    protected File projectDirectory;

    @Argument(alias = "t", description = "Test class name")
    protected String testClassName;
    
    @Argument(alias = "ff", description = "Fail fast. "
            + "If set to true, the tests will stop at the first failure and the next patch will be executed. "
            + "You probably don't want to set this to true for Automatic Program Repair.")
    protected Boolean failFast = false;

    private SourceFileLine sourceFileLine;
    private SourceFileTree sourceFileTree;
    private InternalTestRunner testRunner;

    protected Project project = null;

    // Instantiate a class and call search
    public static void main(String[] args) {
        PatchAnalyser analyser = new PatchAnalyser(args);
        analyser.analyse();
    }

    PatchAnalyser(String[] args) {

        Args.parseOrExit(this, args);

        this.sourceFileLine = new SourceFileLine(source.getAbsolutePath(), null);

        this.sourceFileTree = new SourceFileTree(source.getAbsolutePath(), null);

        if (this.classPath == null) {
            this.project = new Project(projectDirectory, projectName);
            if (mavenHome != null) {
                this.project.setMavenHome(mavenHome);
            } else if(this.project.isMavenProject()) {
                // In case it is indeed a Maven project, tries to find maven in
                // the System's evironment variables and set the path to it.
                Logger.info("I'm going to try and find your maven home, but make sure to set mavenHome for maven projects in the future.");
                this.project.setMavenHome(MavenUtils.findMavenHomeFile());
            }
            Logger.info("Calculating classpath..");
            this.classPath = project.classpath();
            Logger.info("Classpath: " + this.classPath);
        }

        if (this.className == null) {
            this.className = FilenameUtils.removeExtension(this.source.getName());
        }
        if (this.testClassName == null) {
            this.testClassName = this.className + "Test";
        }

    }

    private void analyse() {

        // Create SourceFile and tester classes, parse the patch and generate patched source.
        SourceFileLine sourceFileLine = new SourceFileLine(source.getAbsolutePath(), null);
        SourceFileTree sourceFileTree = new SourceFileTree(source.getAbsolutePath(), null);

        this.testRunner = new InternalTestRunner(className, classPath, testClassName, failFast);

        // Dump statement numbering to a file
        String statementNumbering = sourceFileTree.statementList();
        String statementFilename = source + ".statements";
        try {
            FileUtils.writeStringToFile(new File(statementFilename), statementNumbering, Charset.defaultCharset());
        } catch (IOException e) {
            Logger.error("Could not write statements to " + statementFilename);
            Logger.trace(e);
            System.exit(-1);
        }

        Logger.info("Statement numbering written to: " + statementFilename);

        // Dump block numbering to a file
        String blockNumbering = sourceFileTree.blockList();
        String blockFilename = source + ".blocks";
        try {
            FileUtils.writeStringToFile(new File(blockFilename), blockNumbering, Charset.defaultCharset());
        } catch (IOException e) {
            Logger.error("Could not write blocks to " + blockFilename);
            Logger.trace(e);
            System.exit(-1);
        }
        Logger.info("Block numbering written to: " + blockFilename);

        Patch patch = parsePatch(patchText, sourceFileLine, sourceFileTree);
        String patchedSource = patch.apply();

        Logger.info("Evaluating patch for Source: " + source);

        Logger.info("Patch is: " + patchText);

        // Write the patched source to file, for reference
        String patchedFilename = source + ".patched";
        try {
            FileUtils.writeStringToFile(new File(patchedFilename), patchedSource, Charset.defaultCharset());
        } catch (IOException e) {
            Logger.error("Could not write patched source to " + patchedFilename);
            Logger.trace(e);
            System.exit(-1);
        }
        Logger.info("Parsed patch written to: " + patchedFilename);

        // Evaluate original class
        Logger.info("Timing original class execution...");
        Patch emptyPatch = new Patch(sourceFileTree);
        long originalExecutionTime = testRunner.runTests(emptyPatch, REPS).totalExecutionTime();
        Logger.info("Original execution time: " + originalExecutionTime);

        // Write the original source to file, for easy diff with *.patched file
        patchedFilename = source + ".original";
        try {
            FileUtils.writeStringToFile(new File(patchedFilename), emptyPatch.apply(), Charset.defaultCharset());
        } catch (IOException e) {
            Logger.error("Could not write patched source to " + patchedFilename);
            Logger.trace(e);
            System.exit(-1);
        }
        Logger.info("Parsed patch written to: " + patchedFilename);

        // Evaluate patch
        Logger.info("Timing patched sourceFile execution...");

        UnitTestResultSet resultSet = testRunner.runTests(patch, REPS);

        // Output test results
        logTestResults(resultSet);

        Logger.info("Execution time of patched sourceFile: " + resultSet.totalExecutionTime());
        float speedup = 100.0f * ((originalExecutionTime - resultSet.totalExecutionTime()) /
                (1.0f * originalExecutionTime));
        if (resultSet.getValidPatch() && resultSet.getCleanCompile()) {
            Logger.info("Speedup (%): " + speedup);
        } else {
            Logger.info("Speedup (%): not applicable");
        }

    }

    private static Patch parsePatch(String patchText, SourceFileLine sourceFileLine, SourceFileTree sourceFileTree) {

        if (patchText.equals("|")) {
            Logger.info("No edits to be applied. Running original code.");
            Patch patch = new Patch(sourceFileTree);
            return patch;
        }

        List<Edit> editInstances = new ArrayList<>();
        
        String patchTrim = patchText.trim();
        String cleanPatch = patchTrim;

        if (patchTrim.startsWith("|")) {
            cleanPatch = patchText.replaceFirst("\\|", "").trim();
        }

        String[] editStrings = cleanPatch.trim().split("\\|");
        
        boolean allLineEdits = true;
        boolean allStatementEdits = true;
        
        for (String editString: editStrings) {

            String[] tokens = editString.trim().split("\\s+");

            String editAction = tokens[0];

            Class<?> clazz = null;

            try {
                clazz = Class.forName(editAction);
            } catch (ClassNotFoundException e) {
                Logger.error("Patch edit type unrecognised: " + editAction);
                Logger.trace(e);
                System.exit(-1);
            }

            Method parserMethod = null;
            try {
                parserMethod = clazz.getMethod("fromString", String.class);
            } catch (NoSuchMethodException e) {
                Logger.error("Patch edit type has no fromString method: " + clazz.getCanonicalName());
                Logger.trace(e);
                System.exit(-1);
            }

            Edit editInstance = null;
            try {
                editInstance = (Edit) parserMethod.invoke(null, editString.trim());
            } catch (IllegalAccessException e) {
                Logger.error("Cannot parse patch: access error invoking edit class.");
                Logger.trace(e);
                System.exit(-1);
            } catch (InvocationTargetException e) {
                Logger.error("Cannot parse patch: invocation error invoking edit class.");
                Logger.trace(e);
                System.exit(-1);
            }

            allLineEdits &= editInstance.getEditType() == EditType.LINE;
            allStatementEdits &= editInstance.getEditType() != EditType.LINE;
            editInstances.add(editInstance);
            
        }
        
        if (!allLineEdits && !allStatementEdits) {
            Logger.error("Cannot proceed: mixed line/statement edit types found in patch");
            System.exit(-1);
        }
        
        Patch patch = new Patch(allLineEdits ? sourceFileLine : sourceFileTree);
        for (Edit e : editInstances) {
            patch.add(e);
        }

        return patch;

    }

    private static void logTestResults(UnitTestResultSet results) {

        Logger.info("Test Results");
        Logger.info("Number of results: " + results.getResults().size());
        Logger.info("Valid patch: " + results.getValidPatch());
        Logger.info("Cleanly compiled: " + results.getCleanCompile());
        Logger.info("All tests successful: " + results.allTestsSuccessful());
        Logger.info("Total execution time: " + results.totalExecutionTime());
        Logger.info("Memory Use: " + results.totalMemoryUsage());


        for (UnitTestResult result: results.getResults()) {
            Logger.info(result);
        }

    }

}
