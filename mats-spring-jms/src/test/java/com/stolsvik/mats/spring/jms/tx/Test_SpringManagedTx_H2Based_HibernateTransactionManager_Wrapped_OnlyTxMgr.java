package com.stolsvik.mats.spring.jms.tx;

import javax.sql.DataSource;

import org.junit.runner.RunWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.hibernate5.LocalSessionFactoryBean;
import org.springframework.test.context.junit4.SpringRunner;

import com.stolsvik.mats.spring.EnableMats;

/**
 * Testing Spring DB Transaction management, using HibernateTransactionManager where the DataSource is wrapped only for
 * the TransactionManager - for the other users (JdbcTemplate etc), it is not wrapped.
 *
 * @author Endre Stølsvik 2020-06-05 00:10 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
public class Test_SpringManagedTx_H2Based_HibernateTransactionManager_Wrapped_OnlyTxMgr
        extends Test_SpringManagedTx_H2Based_HibernateTransactionManager {
    @Configuration
    @EnableMats
    static class SpringConfiguration_Hibernate_Wrapped extends SpringConfiguration_HibernateTxMgr {
        @Bean
        LocalSessionFactoryBean createHibernateSessionFactory(DataSource dataSource) {
            // This is a FactoryBean that creates a Hibernate SessionFactory working with Spring's HibernateTxMgr
            LocalSessionFactoryBean factory = new LocalSessionFactoryBean();
            // Setting the DataSource
            factory.setDataSource(
                    JmsMatsTransactionManager_JmsAndSpringManagedSqlTx.wrapLazyConnectionDatasource(dataSource));
            // Setting the single annotated Entity test class we have
            factory.setAnnotatedClasses(DataTableDbo.class);
            return factory;
        }
    }
}
