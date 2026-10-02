package io.github.flowable.plus.extension.decision;

import org.flowable.common.engine.impl.cfg.IdGenerator;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.validation.ProcessValidator;
import org.flowable.validation.ProcessValidatorFactory;
import org.flowable.validation.ProcessValidatorImpl;
import org.flowable.validation.validator.Validator;
import org.flowable.validation.validator.ValidatorSet;

import java.util.UUID;
import java.util.function.Supplier;

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
     * 构建一台 standalone in-mem 引擎（固定 H2、建表），引擎自带 26 个内置 {@code Validator}。
     *
     * @return 进程引擎；调用方负责在使用完毕后关闭
     */
    static ProcessEngine build() {
        return build(null);
    }

    /**
     * 构建一台 standalone in-mem 引擎，并把附加 {@code Validator} <b>叠加式</b>装进默认校验集合。
     *
     * <p><b>叠加而非替换</b>：{@code setProcessValidator} 是<b>整体替换</b>语义，直接塞一个自建校验器会
     * 静默摧毁引擎全部内置语义校验 —— 故先取默认实例，再把附加校验器放进<b>新开的一个 {@code ValidatorSet}</b>
     * 后整体赋值。这与 starter 侧的引擎级装配同形（该装配住 starter，本基座只在测试侧等价替换）。</p>
     *
     * @param additionalValidator 附加校验器；<b>null 合法</b>，含义 = 不装任何附加校验器（参照态）
     * @return 进程引擎；调用方负责在使用完毕后关闭
     */
    static ProcessEngine build(final Validator additionalValidator) {
        final ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration()
                .setJdbcUrl(JDBC_URL)
                .setJdbcDriver(org.h2.Driver.class.getName())
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        if (additionalValidator != null) {
            configuration.setProcessValidator(withAdditionalValidatorSet(additionalValidator));
        }
        return configuration.buildProcessEngine();
    }

    /**
     * 构建<b>独立内存库</b>的引擎（库名每次随机；同一 JVM 内多台引擎互不见对方的表）。
     *
     * <p>供需要<b>同时</b>持有两台以上引擎的落点使用（无感等价对拍：关态 / 参照态 / 开态各一台）。
     * 其余形态与 {@link #build(Validator)} 完全一致；两台引擎共享一个固定 H2 库名会让彼此的部署、
     * 实例与作业互相污染，「同一份 BPMN + 同一段操作序列」就比错了对象。</p>
     *
     * @param additionalValidator 附加校验器；<b>null 合法</b>，含义 = 不装任何附加校验器（参照态）
     * @return 进程引擎；调用方负责在使用完毕后关闭
     */
    static ProcessEngine buildIsolated(final Validator additionalValidator) {
        return buildIsolated(additionalValidator, null);
    }

    /**
     * 构建独立内存库的引擎，并把引擎的 {@code IdGenerator} 替换为给定供给源。
     *
     * <p>供「{@code ID_} 不可解析」的降级路径使用（ADR-0042 第 9 节第 7 条预设的应用侧替换场景）。
     * 入参取 {@code Supplier<String>} 而非引擎内建的 {@code IdGenerator} 类型 —— 后者住引擎的非公开包，
     * 击穿实验的承载文件不 import 非公开包（准入条件 ① 的受限扫描面）；适配在本基座内完成（本类是
     * 测试基座、非击穿实验落点）。</p>
     *
     * @param additionalValidator 附加校验器；<b>null 合法</b>
     * @param idSupplier          标识供给源；<b>null 合法</b>，含义 = 保留引擎默认（{@code DbIdGenerator}）
     * @return 进程引擎；调用方负责在使用完毕后关闭
     */
    static ProcessEngine buildIsolated(final Validator additionalValidator, final Supplier<String> idSupplier) {
        return buildIsolated(additionalValidator, idSupplier, "ext-" + UUID.randomUUID());
    }

    /**
     * 构建独立内存库的引擎，库名由调用方指定（同一次装配内需要对库直连取证时用确定地址取回同一库）。
     *
     * @param additionalValidator 附加校验器；<b>null 合法</b>
     * @param idSupplier           标识供给源；<b>null 合法</b>
     * @param dbName               内存库名（调用方保证互异）
     * @return 进程引擎；调用方负责在使用完毕后关闭
     */
    static ProcessEngine buildIsolated(final Validator additionalValidator, final Supplier<String> idSupplier,
                                       final String dbName) {
        final String isolatedUrl = isolatedJdbcUrl(dbName);
        final ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration()
                .setJdbcUrl(isolatedUrl)
                .setJdbcDriver(org.h2.Driver.class.getName())
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        if (additionalValidator != null) {
            configuration.setProcessValidator(withAdditionalValidatorSet(additionalValidator));
        }
        if (idSupplier != null) {
            configuration.setIdGenerator(idSupplier::get);
        }
        return configuration.buildProcessEngine();
    }

    /**
     * 指定名的独立内存库 JDBC 地址（与 {@link #buildIsolated(Validator, Supplier, String)} 的库名规则一致，
     * 供同一装配内对库直连取证复用同一连接串）。
     *
     * @param dbName 内存库名
     * @return JDBC URL
     */
    static String isolatedJdbcUrl(String dbName) {
        return "jdbc:h2:mem:flowable-plus-" + dbName + ";DB_CLOSE_DELAY=-1";
    }

    /**
     * 本基座固定 H2 库的 JDBC 地址（供需要对引擎数据库直连取证 —— 例如构造「同一毫秒写入」的
     * 行状态 —— 的落点复用同一连接串；引擎自身无「改写历史行时间」的公开位点，行状态属 fixture 构造）。
     *
     * @return 固定 JDBC URL
     */
    static String jdbcUrl() {
        return JDBC_URL;
    }

    /**
     * 自建默认校验器实例，并把附加校验器放进具名 {@code ValidatorSet} 后叠加。
     *
     * <p><b>与生产装配同形的如实披露</b>：生产侧的主闸装配（{@code EngineConfigurationConfigurer} + 同一
     * 叠加式形态）住 starter；两处形态相同是<b>有意</b>的 —— 本基座不引 Spring，测试态拿不到 starter 的装配。
     * 故本方法<b>只服务测试态</b>，不构成生产装配的第二真相；形态依据 = 无感等价对拍的形态文件 §2.5
     * （主闸在 extension 测试里以「自建默认 {@code ProcessValidator} + 叠加 {@code ValidatorSet}」等价替换）。</p>
     *
     * @param additionalValidator 附加校验器
     * @return 默认实例 + 附加集合的校验器
     */
    private static ProcessValidator withAdditionalValidatorSet(final Validator additionalValidator) {
        final ProcessValidator defaultValidator = new ProcessValidatorFactory().createDefaultProcessValidator();
        final ValidatorSet validatorSet = new ValidatorSet(DecisionNodeDeclarationValidator.VALIDATOR_SET_NAME);
        validatorSet.addValidator(additionalValidator);
        ((ProcessValidatorImpl) defaultValidator).addValidatorSet(validatorSet);
        return defaultValidator;
    }
}
