package com.stolsvik.mats.test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.jms.ConnectionFactory;
import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stolsvik.mats.MatsFactory;
import com.stolsvik.mats.impl.jms.JmsMatsFactory;
import com.stolsvik.mats.util.MatsStringSerializer;

/**
 * Provides a H2 Database DataSource, and a
 * {@link JmsMatsFactory#createMatsFactory_JmsAndJdbcTransactions(com.stolsvik.mats.impl.jms.JmsMatsTransactionManager.JmsConnectionSupplier, com.stolsvik.mats.impl.jms.JmsMatsTransactionManager_JmsAndJdbc.JdbcConnectionSupplier, MatsStringSerializer)
 * JmsAndJdbc MATS Transaction Manager}, in addition to features from {@link Rule_Mats}. This enables testing of
 * combined JMS and JDBC scenarios - in particularly used for testing of the MATS library itself (check that commits and
 * rollbacks work as expected).
 * <p>
 * Notice that the H2 Database is started with the "AUTO_SERVER=TRUE" flag that starts the
 * <a href="http://www.h2database.com/html/features.html#auto_mixed_mode">Auto Mixed Mode</a>, meaning that you can
 * connect to the same URL from a different process. This can be of value if you wonder what is in the database at any
 * point: You may do a "Thread.sleep(60000)" at some point in the code, and then connect to the same URL using e.g.
 * <a href="http://www.squirrelsql.org/">SquirrelSQL</a>, and this second H2 client will magically be able to connect to
 * the database by using a TCP socket to the original.
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class Rule_MatsWithDb extends Rule_Mats {
    private static final Logger log = LoggerFactory.getLogger(Rule_MatsWithDb.class);

    private JdbcDataSource _dataSource;

    @Override
    protected void before() throws Throwable {
        // Set up JMS from super
        super.before();
        log.info("+++ BEFORE on JUnit Rule '" + Rule_MatsWithDb.class.getSimpleName() + "'.");
        log.info("Setting up H2 database.");
        // Set up H2 Database
        _dataSource = new JdbcDataSource();
        _dataSource.setURL("jdbc:h2:./matsTestH2DB;AUTO_SERVER=TRUE");
        try {
            try (Connection con = _dataSource.getConnection();
                    Statement stmt = con.createStatement();) {
                stmt.execute("DROP ALL OBJECTS DELETE FILES");
            }
        }
        catch (SQLException e) {
            throw new RuntimeException("Got problems running 'DROP ALL OBJECTS DELETE FILES'.", e);
        }
    }

    @Override
    protected MatsFactory createMatsFactory(MatsStringSerializer stringSerializer,
            ConnectionFactory jmsConnectionFactory) {
        // Create the JMS and JDBC TransactionManager-backed JMS MatsFactory.
        return JmsMatsFactory.createMatsFactory_JmsAndJdbcTransactions((s) -> jmsConnectionFactory.createConnection(),
                (s) -> _dataSource.getConnection(), _matsStringSerializer);
    }

    /**
     * @return the H2 {@link DataSource} instance which transaction manager of the MatsFactory is set up with.
     */
    public DataSource getDataSource() {
        return _dataSource;
    }

    /**
     * @return a SQL Connection <b>directly from the {@link #getDataSource()}</b>, thus not a part of the Mats
     *         transactional infrastructure.
     */
    public Connection getNonTxConnection() {
        try {
            return getDataSource().getConnection();
        }
        catch (SQLException e) {
            throw new DatabaseException("Could not .getConnection() on the DataSource [" + getDataSource() + "].", e);
        }
    }

    /**
     * <b>Using a SQL Connection from {@link #getDataSource()}</b>, this method creates the SQL Table 'datatable',
     * containing one column 'data'.
     */
    public void createDataTable() {
        try {
            try (Connection dbCon = getNonTxConnection();
                    Statement stmt = dbCon.createStatement();) {
                stmt.execute("CREATE TABLE datatable ( data VARCHAR )");
            }
        }
        catch (SQLException e) {
            throw new DatabaseException("Got problems creating the SQL 'datatable'.", e);
        }
    }

    /**
     * Inserts the provided 'data' into the SQL Table 'datatable', using the provided SQL Connection.
     *
     * @param sqlConnection
     *            the SQL Connection to use to insert data.
     * @param data
     *            the data to insert.
     */
    public void insertDataIntoDataTable(Connection sqlConnection, String data) {
        // :: Populate the SQL table with a piece of data
        try {
            PreparedStatement pStmt = sqlConnection.prepareStatement("INSERT INTO datatable VALUES (?)");
            pStmt.setString(1, data);
            pStmt.execute();
        }
        catch (SQLException e) {
            throw new DatabaseException("Got problems with the SQL", e);
        }

    }

    /**
     * @param sqlConnection
     *            the SQL Connection to use to fetch data.
     * @return the sole row and sole column from 'datatable', throws if not present.
     */
    public String getDataFromDataTable(Connection sqlConnection) {
        try {
            Statement stmt = sqlConnection.createStatement();
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM datatable")) {
                rs.next();
                return rs.getString(1);
            }
        }
        catch (SQLException e) {
            throw new DatabaseException("Got problems fetching column 'data' from SQL Table 'datatable'", e);
        }
    }

    /**
     * A {@link RuntimeException} for use in database access methods and tests.
     */
    public static class DatabaseException extends RuntimeException {
        public DatabaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}