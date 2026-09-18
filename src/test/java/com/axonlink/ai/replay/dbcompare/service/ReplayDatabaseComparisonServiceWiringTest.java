package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonDao;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonVersionDao;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonVersionScriptDao;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class ReplayDatabaseComparisonServiceWiringTest {

    @Test
    void springCreatesServiceWithProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            DataSource dataSource = mock(DataSource.class);
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
            org.mockito.Mockito.when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
            context.registerBean(ReplayBaseMetadataService.class,
                    () -> mock(ReplayBaseMetadataService.class));
            context.registerBean(ReplayDatabaseComparisonDao.class,
                    () -> mock(ReplayDatabaseComparisonDao.class));
            context.registerBean(ReplayDatabaseComparisonVersionDao.class,
                    () -> mock(ReplayDatabaseComparisonVersionDao.class));
            context.registerBean(ReplayDatabaseComparisonVersionScriptDao.class,
                    () -> mock(ReplayDatabaseComparisonVersionScriptDao.class));
            context.registerBean(SysUserDao.class, () -> mock(SysUserDao.class));
            context.registerBean(ReplayDatabaseComparisonAuditDiff.class,
                    ReplayDatabaseComparisonAuditDiff::new);
            context.registerBean(ReplayDatabaseComparisonConditionCodec.class,
                    () -> new ReplayDatabaseComparisonConditionCodec());
            context.registerBean(ReplayDatabaseComparisonScopeCompiler.class,
                    () -> new ReplayDatabaseComparisonScopeCompiler(
                            context.getBean(ReplayDatabaseComparisonConditionCodec.class)));
            context.registerBean(ReplayDatabaseComparisonExcelParser.class,
                    () -> mock(ReplayDatabaseComparisonExcelParser.class));
            context.registerBean(ReplayDatabaseComparisonConfigurationHasher.class,
                    ReplayDatabaseComparisonConfigurationHasher::new);
            context.registerBean(ReplayDatabaseComparisonConfigScriptGenerator.class,
                    ReplayDatabaseComparisonConfigScriptGenerator::new);
            context.registerBean(JdbcTemplate.class, () -> jdbcTemplate);
            context.register(ReplayDatabaseComparisonService.class);
            context.register(ReplayDatabaseComparisonImportService.class);
            context.register(ReplayDatabaseComparisonVersionService.class);
            context.register(ReplayDatabaseComparisonConfigScriptService.class);

            assertThatCode(context::refresh).doesNotThrowAnyException();
            assertThat(context.getBean(ReplayDatabaseComparisonService.class)).isNotNull();
            assertThat(context.getBean(ReplayDatabaseComparisonImportService.class)).isNotNull();
            assertThat(context.getBean(ReplayDatabaseComparisonVersionService.class)).isNotNull();
            assertThat(context.getBean(ReplayDatabaseComparisonConfigScriptService.class)).isNotNull();
        }
    }
}
