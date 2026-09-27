package io.github.flowable.plus.extension.decision;

import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.validation.ProcessValidator;
import org.flowable.validation.ProcessValidatorFactory;
import org.flowable.validation.ProcessValidatorImpl;
import org.flowable.validation.validator.Validator;
import org.flowable.validation.validator.ValidatorSet;

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
