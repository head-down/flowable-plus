package io.github.flowable.plus.starter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试抽象基类，通过系统属性 {@code flowable.test.db} 控制测试数据库。
 *
 * <pre>
 *   h2         — 内存 H2（默认，零开销，本地开发使用）
 *   mysql      — Testcontainers MySQL 8.0
 *   postgresql — Testcontainers PostgreSQL 14
 * </pre>
 *
 * 使用方式：
 * <pre>
 * mvn test -Dflowable.test.db=mysql -pl flowable-plus-spring-boot-starter
 * mvn test -Dflowable.test.db=postgresql -pl flowable-plus-spring-boot-starter
 * </pre>
 * 不加参数时默认使用 H2。
 */
public abstract class AbstractIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractIntegrationTest.class);

    private static final String ACTIVE_DB = System.getProperty("flowable.test.db", "h2");

    private static MySQLContainer<?> mysqlContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    static {
        if ("mysql".equals(ACTIVE_DB)) {
            LOG.info("=== 启动 MySQL 8.0 容器 ===");
            mysqlContainer = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("flowable_test")
                    .withUsername("flowable")
                    .withPassword("flowable");
            mysqlContainer.start();
            LOG.info("MySQL 容器已就绪: {}", mysqlContainer.getJdbcUrl());
        } else if ("postgresql".equals(ACTIVE_DB)) {
            LOG.info("=== 启动 PostgreSQL 14 容器 ===");
            postgresContainer = new PostgreSQLContainer<>(DockerImageName.parse("postgres:14"))
                    .withDatabaseName("flowable_test")
                    .withUsername("flowable")
                    .withPassword("flowable");
            postgresContainer.start();
            LOG.info("PostgreSQL 容器已就绪: {}", postgresContainer.getJdbcUrl());
        } else {
            LOG.info("=== 使用 H2 内存数据库（默认） ===");
        }
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        if ("mysql".equals(ACTIVE_DB) && mysqlContainer != null && mysqlContainer.isRunning()) {
            registry.add("spring.datasource.url", mysqlContainer::getJdbcUrl);
            registry.add("spring.datasource.username", mysqlContainer::getUsername);
            registry.add("spring.datasource.password", mysqlContainer::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        } else if ("postgresql".equals(ACTIVE_DB) && postgresContainer != null && postgresContainer.isRunning()) {
            registry.add("spring.datasource.url", postgresContainer::getJdbcUrl);
            registry.add("spring.datasource.username", postgresContainer::getUsername);
            registry.add("spring.datasource.password", postgresContainer::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        }
        // h2: 使用 application.yml 中的默认值，无需覆盖
    }

    protected static String getActiveDb() {
        return ACTIVE_DB;
    }

    protected static boolean isH2() {
        return "h2".equals(ACTIVE_DB);
    }

    protected static boolean isMySQL() {
        return "mysql".equals(ACTIVE_DB);
    }

    protected static boolean isPostgreSQL() {
        return "postgresql".equals(ACTIVE_DB);
    }
}
