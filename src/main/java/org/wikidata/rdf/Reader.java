package org.wikidata.rdf;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;
import java.util.function.Consumer;

import org.openrdf.repository.RepositoryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bigdata.rdf.model.BigdataStatement;
import com.bigdata.rdf.sail.BigdataSail;
import com.bigdata.rdf.sail.BigdataSailRepository;
import com.bigdata.rdf.sail.BigdataSailRepositoryConnection;
import com.bigdata.rdf.store.AbstractTripleStore;
import com.bigdata.rdf.store.BigdataStatementIterator;
import com.codahale.metrics.Meter;
import com.google.common.base.Charsets;
import com.google.common.primitives.Ints;
import com.lexicalscope.jewel.cli.Cli;
import com.lexicalscope.jewel.cli.CliFactory;
import com.lexicalscope.jewel.cli.Option;

public class Reader {
    private static final Logger log = LoggerFactory.getLogger(Reader.class);

    private static final long MAP_SIZE = Integer.MAX_VALUE;

    private final Meter statementMeter = new Meter();

    private final MessageDigest digest;

    /**
     * Basic options for parsing with JewelCLI.
     */
    @SuppressWarnings("checkstyle:javadocmethod")
    public interface Options {
        @Option(shortName = "v", description = "Verbose mode")
        boolean verbose();

        @Option(helpRequest = true, description = "Show this message")
        boolean help();

        @Option(shortName = "c", description = "Check the map")
        boolean check();

        @Option(shortName = "f", description = "Input file (RWstore.properties)")
        String input();

        @Option(shortName = "m", description = "Map file")
        String map();
    }

    public static void main(String[] args) {
        try {
            Cli<Options> cli = CliFactory.createCli(Options.class);
            Options options = cli.parseArguments(args);
            Reader r = new Reader();
            if (options.check()) {
                r.check(options.input(), options.map());
            } else {
                r.read(options.input(), options.map());
            }
        } catch (RepositoryException | NoSuchAlgorithmException | IOException e) {
            e.printStackTrace();
        }
    }

    public Reader() throws NoSuchAlgorithmException {
        this.digest = MessageDigest.getInstance("MD5");
    }

    private byte[] getHash(BigdataStatement statement) {
        digest.reset();
        digest.update(statement.getSubject().stringValue().getBytes(Charsets.UTF_8));
        digest.update(statement.getPredicate().stringValue().getBytes(Charsets.UTF_8));
        digest.update(statement.getObject().stringValue().getBytes(Charsets.UTF_8));
        return digest.digest();
    }

    public void read(String from, String mapFileName) throws RepositoryException, NoSuchAlgorithmException, IOException {
        try(RandomAccessFile mapFile = new RandomAccessFile(mapFileName, "rw")) {
            FileChannel channel = mapFile.getChannel();
            final MappedByteBuffer mappedByteBuffer = channel.map(MapMode.READ_WRITE, 0, MAP_SIZE);

            try {
                process(from, statement -> {
                    byte[] hash = getHash(statement);
                    int index = Ints.fromByteArray(hash);
                    if (index < 0) {
                        // Map to positives
                        index = -index;
                    }
                    mappedByteBuffer.put(index, (byte) (mappedByteBuffer.get(index) | 1 << (hash[10] & 7)));
                });
            } finally {
                mappedByteBuffer.force();
            }
        }
    }

    public void check(String from, String mapFileName) throws RepositoryException, NoSuchAlgorithmException, IOException {
        try(RandomAccessFile mapFile = new RandomAccessFile(mapFileName, "r")) {
            FileChannel channel = mapFile.getChannel();
            final MappedByteBuffer mappedByteBuffer = channel.map(MapMode.READ_ONLY, 0, MAP_SIZE);
            process(from, statement -> {
                byte[] hash = getHash(statement);
                int index = Ints.fromByteArray(hash);
                if (index < 0) {
                    // Map to positives
                    index = -index;
                }
                if ((mappedByteBuffer.get(index) & 1 << (hash[10] & 7)) == 0) {
                    log.error("Did not find: {}", statement);
                }
            });
        }
        log.info("Done.");
    }

    public void process(String from, Consumer<BigdataStatement> func) throws IOException, RepositoryException {
        final Properties props = new Properties();
        props.load(Files.newBufferedReader(new File(from).toPath(),
                Charset.forName("US-ASCII")));
        props.setProperty("com.bigdata.rdf.sail.namespace", "wdq");
        final BigdataSail sail = new BigdataSail(props);
        final BigdataSailRepository repo = new BigdataSailRepository(sail);
        repo.initialize();
        BigdataSailRepositoryConnection cxn = repo.getReadOnlyConnection();
        AbstractTripleStore store = cxn.getTripleStore();

        try {
            log.info("Statements: {}", store.getStatementCount(false));

//            final IAccessPath<ISPO> ap = store.getSPORelation().getAccessPath(null, null, null, null);
            final BigdataStatementIterator itr = store.getStatements(null, null, null, null);
//                    store.asStatementIterator(ap.iterator());
            while (itr.hasNext()) {
                final BigdataStatement statement = itr.next();
                func.accept(statement);
                statementMeter.mark();
                if (statementMeter.getCount() % 10000 == 0) {
                    log.info("Processed {} statements at ({}, {}, {})",
                            statementMeter.getCount(),
                            (long) statementMeter.getOneMinuteRate(),
                            (long) statementMeter.getFiveMinuteRate(),
                            (long) statementMeter.getFifteenMinuteRate());
                }
            }
        } catch(Exception e) {
            log.error("Oops: " + e);
            throw e;
        } finally {
            cxn.close();
            repo.shutDown();
        }
        log.info("Processed {} statements at ({}, {}, {})",
                statementMeter.getCount(),
                (long) statementMeter.getOneMinuteRate(),
                (long) statementMeter.getFiveMinuteRate(),
                (long) statementMeter.getFifteenMinuteRate());
    }
}
