package gin.edit.statement;

import java.util.Random;

import gin.SourceFile;
import gin.SourceFileTree;
import gin.edit.Edit;
import gin.misc.BlockedByJavaParserException;

public class DeleteStatement extends StatementEdit {

    private static final long serialVersionUID = -8946372835353498185L;
    private String sourceFilename;
    private int statementToDelete;

    /** 
     * create a random deletestatement for the given sourcefile, using the provided RNG
     * @param sourceFile to create an edit for
     * @param rng random number generator, used to choose the target statements
     * */
    public DeleteStatement(SourceFile sourceFile, Random rng) {
        this(sourceFile.getFilename(), ((SourceFileTree)sourceFile).getRandomStatementID(true, rng));
    }
    
    public DeleteStatement(String filename, int statementToDelete) {
        this.sourceFilename = filename;
        this.statementToDelete = statementToDelete;
    }
    
    @Override
    public SourceFile apply(SourceFile sourceFile) {
        SourceFileTree sf = (SourceFileTree)sourceFile;
        try {
            return sf.removeStatement(statementToDelete);
        } catch (BlockedByJavaParserException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return this.getClass().getCanonicalName() + " \"" + sourceFilename + "\":" + statementToDelete;
    }

    public static Edit fromString(String description) {
            String[] tokens = description.split("\\s+(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
        String[] tokens2 = tokens[1].split(":");
        String filename = tokens2[0].replace("\"", "");
        int statement = Integer.parseInt(tokens2[1]);
        return new DeleteStatement(filename, statement);
    }

}
