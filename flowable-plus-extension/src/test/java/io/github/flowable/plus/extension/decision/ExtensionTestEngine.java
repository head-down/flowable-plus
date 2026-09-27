package io.github.flowable.plus.extension.decision;

import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;

/**
 * extension 的真实引擎测试基座（<b>测试专用类型，非测试类</b>）。
 *
 * <p><b>形态</b>：standalone in-mem 引擎，数据库<b>固定 H2</b>；<b>不读</b>
 * {@code flowable.test.db}、<b>不引</b> Spring、<b>不引</b> Testcontainers。由此 extension 的真引擎
 * 测试在既有 CI 矩阵的<b>四个 job 上行为一致</b>（不产生分叉、不新增矩阵轴）。</p>
 *
 * <p><b>消费者</b>：需要真引擎产出的落点（部署期校验结果、真 {@code ID_} 序列与 SQL 排序、
 * 无感等价对拍）—— 即 {@code E3} / {@code E12} / {@code E20} 家族。本基座由「工程基座」这一票先行
 * 落地，使后续落点不必各自自举引擎。</p>
 */
final class ExtensionTestEngine {

    /** 固定 H2 内存库名（刻意与 {@code flowable.test.db} 无关：本基座不看该属性） */
    private static final String JDBC_URL = "jdbc:h2:mem:flowable-plus-extension;DB_CLOSE_DELAY=-1";

    private ExtensionTestEngine() {
    }

    /**
     * 构建一台 standalone in-mem 引擎（固定 H2、建表）。
     *
     * @return 进程引擎；调用方负责在使用完毕后关闭
     */
    static ProcessEngine build() {
        return ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
                .setJdbcUrl(JDBC_URL)
                .setJdbcDriver(org.h2.Driver.class.getName())
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
                .buildProcessEngine();
    }
}
