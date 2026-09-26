package io.github.flowable.plus.core.enums;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * core 具名常量的形状断言（测试树内部共用，避免同一形状在多个守卫里各写一遍）。
 */
final class ConstantFieldAssertions {

    private ConstantFieldAssertions() {
    }

    /**
     * 断言字段是<b>公开 static final</b> 的整型常量，且声明在该护栏常量类内。
     *
     * @param field 待检字段
     * @param owner 期望的声明类
     */
    static void assertPublicStaticFinalNumericConstant(final Field field, final Class<?> owner) {
        assertThat(Modifier.isPublic(field.getModifiers()))
                .as("%s 必须是公开具名常量", field.getName())
                .isTrue();
        assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        assertThat(field.getType())
                .as("%s 必须是整型常量（字节数 / 层数）", field.getName())
                .isIn(int.class, long.class);
        assertThat(field.getDeclaringClass()).isEqualTo(owner);
    }

    /**
     * 断言字段是<b>私有 static final String</b>（公开面不得暴露此类常量）。
     *
     * @param field 待检字段
     * @return 形状成立返回 true
     */
    static boolean isPrivateStaticFinalString(final Field field) {
        return Modifier.isPrivate(field.getModifiers())
                && Modifier.isStatic(field.getModifiers())
                && Modifier.isFinal(field.getModifiers())
                && field.getType() == String.class;
    }
}
