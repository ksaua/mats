package com.stolsvik.mats.spring.test.testapp_two_mf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import javax.jms.ConnectionFactory;

import com.stolsvik.mats.test.MatsTestLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import com.stolsvik.mats.MatsFactory;
import com.stolsvik.mats.serial.MatsSerializer;
import com.stolsvik.mats.serial.json.MatsSerializer_DefaultJson;
import com.stolsvik.mats.spring.EnableMats;
import com.stolsvik.mats.spring.jms.factories.JmsSpringConnectionFactoryProducer;
import com.stolsvik.mats.spring.jms.factories.JmsSpringMatsFactoryProducer;
import com.stolsvik.mats.util_activemq.MatsLocalVmActiveMq;

/**
 * A simple test application using Mats and Mats' SpringConfig.
 * <p>
 * PLEASE NOTE: In this "application", we set up two MatsLocalVmActiveMq in-vm "LocalVM" instances to simulate a
 * production setup where there are two external Message Brokers that this application wants to connect to. The reason
 * is that it should be possible to run this test-application without external resources set up. To connect to these
 * brokers, start the application with Spring Profile "mats-regular" active, or set the system property "mats.regular"
 * (i.e. "-Dmats.regular" on the Java command line). However, if the Spring Profile "mats-test" is active (which you do
 * in integration tests), the JmsSpringConnectionFactoryProducer will instead of using the specified ConnectionFactory
 * to these two message brokers, make new LocalVM instances and return a ConnectionFactory to those. Had this been a
 * real application, where the ConnectionFactory specified in those beans pointed to the production brokers, this would
 * make it possible to switch between connecting to the production setup, and the integration testing setup.
 * 
 * @author Endre Stølsvik 2019-05-17 21:42 - http://stolsvik.com/, endre@stolsvik.com
 */
@Configuration
@EnableMats
@ComponentScan(basePackageClasses = Main_TwoMf.class)
public class Main_TwoMf {
    public static final String ENDPOINT_ID = "TestApp_TwoMf";

    private static final Logger log = LoggerFactory.getLogger(Main_TwoMf.class);

    public static void main(String... args) {
        new Main_TwoMf().start();
    }

    private void start() {
        long nanosStart = System.nanoTime();
        log.info("Starting " + this.getClass().getSimpleName() + "!");
        log.info(" \\- new'ing up AnnotationConfigApplicationContext, giving class [" + this.getClass()
                .getSimpleName() + "] as base.");
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(this.getClass());
        log.info(" \\- done, AnnotationConfigApplicationContext: [" + ctx + "].");

        // ----- Spring is running.

        log.info("Starting application.");
        try {
            TestApplicationBean testApplicationBean = ctx.getBean(TestApplicationBean.class);
            testApplicationBean.run();
        }
        catch (Throwable t) {
            String msg = "Got some Exception when running app.";
            log.error(msg, t);
            throw new RuntimeException(msg, t);
        }
        finally {
            // :: Close Spring.
            ctx.close();

            log.info("Exiting! took " + ((System.nanoTime() - nanosStart) / 1_000_000d) + " ms.");
        }
    }

    @Bean
    public MatsTestLatch matsTestLatch() {
        return new MatsTestLatch();
    }

    @Bean
    public MatsSerializer<String> matsSerializer() {
        return new MatsSerializer_DefaultJson();
    }

    @Bean
    protected MatsLocalVmActiveMq matsTestActiveMq1() {
        log.info("Creating MatsTestActiveMq1");
        return MatsLocalVmActiveMq.createInVmActiveMq("activeMq1");
    }

    @Bean
    protected MatsLocalVmActiveMq matsTestActiveMq2() {
        log.info("Creating MatsTestActiveMq2");
        return MatsLocalVmActiveMq.createInVmActiveMq("activeMq2");
    }

    @Bean
    @Qualifier("connectionFactoryA")
    protected ConnectionFactory jmsConnectionFactory1(@Qualifier("matsTestActiveMq1") MatsLocalVmActiveMq activeMq) {
        log.info("Creating ConnectionFactory with @Qualifier(\"connectionFactoryA\")");
        return new JmsSpringConnectionFactoryProducer()
                .regularConnectionFactory((env) -> activeMq.getConnectionFactory())
                .create();
    }

    @Bean
    @Qualifier("connectionFactoryB")
    protected ConnectionFactory jmsConnectionFactory2(@Qualifier("matsTestActiveMq2") MatsLocalVmActiveMq activeMq) {
        log.info("Creating ConnectionFactory with @Qualifier(\"connectionFactoryB\")");
        return new JmsSpringConnectionFactoryProducer()
                .regularConnectionFactory((env) -> activeMq.getConnectionFactory())
                .create();
    }

    @Bean
    @TestQualifier(endre = "Elg")
    @Qualifier("matsFactoryX")
    protected MatsFactory matsFactory1(@Qualifier("connectionFactoryA") ConnectionFactory connectionFactory,
            MatsSerializer<String> matsSerializer) {
        log.info("Creating MatsFactory1");
        return JmsSpringMatsFactoryProducer.createJmsTxOnlyMatsFactory(this.getClass().getSimpleName(), "#testing#",
                matsSerializer, connectionFactory);
    }

    @Bean
    @Qualifier("matsFactoryY")
    protected MatsFactory matsFactory2(@Qualifier("connectionFactoryB") ConnectionFactory connectionFactory,
            MatsSerializer<String> matsSerializer) {
        log.info("Creating MatsFactory2");
        return JmsSpringMatsFactoryProducer.createJmsTxOnlyMatsFactory(this.getClass().getSimpleName(), "#testing#",
                matsSerializer, connectionFactory);
    }

    @Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER })
    @Retention(RetentionPolicy.RUNTIME)
    @Qualifier
    public @interface TestQualifier {
        String endre() default "";
    }
}