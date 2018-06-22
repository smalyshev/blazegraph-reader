package org.wikidata.rdf;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Properties;

import org.openrdf.model.impl.LiteralImpl;
import org.openrdf.repository.RepositoryException;

import com.bigdata.btree.IIndex;
import com.bigdata.btree.keys.IKeyBuilder;
import com.bigdata.rdf.internal.IV;
import com.bigdata.rdf.internal.IVUtility;
import com.bigdata.rdf.lexicon.LexiconKeyBuilder;
import com.bigdata.rdf.lexicon.LexiconRelation;
import com.bigdata.rdf.lexicon.Term2IdTupleSerializer;
import com.bigdata.rdf.model.BigdataValue;
import com.bigdata.rdf.model.BigdataValueFactory;
import com.bigdata.rdf.sail.BigdataSail;
import com.bigdata.rdf.sail.BigdataSailRepository;
import com.bigdata.rdf.sail.BigdataSailRepositoryConnection;
import com.bigdata.rdf.store.AbstractTripleStore;
import com.lexicalscope.jewel.cli.Cli;
import com.lexicalscope.jewel.cli.CliFactory;
import com.lexicalscope.jewel.cli.Option;

public class TermId {

    private BigdataSailRepositoryConnection cxn;

    /**
     * Basic options for parsing with JewelCLI.
     */
    @SuppressWarnings("checkstyle:javadocmethod")
    public interface Options {
        @Option(helpRequest = true, description = "Show this message")
        boolean help();

        @Option(shortName = "c", description = "Config file (.properties)")
        String input();

        @Option(shortName = "t", description = "Term to check")
        String term();

        @Option(shortName = "f", description = "Fix the problem (will write to the DB!)")
        boolean fix();

        boolean isFix();

    }

    public static void main(String[] args) {
        try {
            Cli<Options> cli = CliFactory.createCli(Options.class);
            Options options = cli.parseArguments(args);
            TermId r = new TermId();
            r.getTermId(options.input(), options.term(), options.isFix());
        } catch (RepositoryException | IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private LexiconRelation getLexicon(BigdataSailRepository repo) throws RepositoryException, InterruptedException {
        if (cxn != null) {
            cxn.commit();
            cxn.close();
        }
        cxn = repo.getUnisolatedConnection();
        AbstractTripleStore store = cxn.getTripleStore();
        return store.getLexiconRelation();
    }

    public void getTermId(String dbFile, String termId, boolean fix) throws IOException, RepositoryException, InterruptedException {
        final Properties props = new Properties();
        props.load(Files.newBufferedReader(new File(dbFile).toPath(),
                Charset.forName("US-ASCII")));
        props.setProperty("com.bigdata.rdf.sail.namespace", "wdq");
        final BigdataSail sail = new BigdataSail(props);
        final BigdataSailRepository repo = new BigdataSailRepository(sail);
        repo.initialize();
        LexiconRelation lexicon = getLexicon(repo);
        IIndex term2id = lexicon.getTerm2IdIndex();
        IIndex id2term = lexicon.getId2TermIndex();
        Term2IdTupleSerializer ser = (Term2IdTupleSerializer)term2id.getIndexMetadata().getTupleSerializer();
        LexiconKeyBuilder lexBuilder = ser.getLexiconKeyBuilder();
        IKeyBuilder keyBuilder = ser.getKeyBuilder();
        final BigdataValueFactory vf = lexicon.getValueFactory();
        byte[] key = lexBuilder.plainLiteral2key(termId);
        byte[] result = term2id.lookup(key);
        IV iv = IVUtility.decode(result);
        //            lexicon.addTerms(terms, 1, false);
        //            System.out.println("Lexicon lookup: " + terms[0].getIV());
        System.out.println("Incoming string[" + termId.length() + "]:" + Arrays.toString(termId.getBytes()));
        System.out.println("Direct lookup: " + iv);
        byte[] ivKey = iv.encode(keyBuilder.reset()).getKey();
        byte[] reverse = id2term.lookup(ivKey);
        System.out.println("Reverse lookup: " + Arrays.toString(reverse));
        BigdataValue val = vf.getValueSerializer().deserialize(reverse);
        System.out.println("Reverse lookup value: " + val);
        String strVal = val.stringValue();
        System.out.println("Reverse lookup String[" + strVal.length() + "]:" + Arrays.toString(strVal.getBytes()));
        if(!strVal.equals(termId)) {
            System.out.println("BAD TERM!");
            if(fix) {
                BigdataValue trueValue = (BigdataValue)vf.asValue(new LiteralImpl(termId));
                id2term.remove(ivKey);
                id2term.putIfAbsent(ivKey, vf.getValueSerializer().serialize(trueValue));
                // commit
                lexicon = getLexicon(repo);
                System.out.println("Fixed, please re-try.");
            }
        }
    }


}
