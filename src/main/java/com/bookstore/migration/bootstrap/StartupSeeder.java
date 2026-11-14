package com.bookstore.migration.bootstrap;

import com.bookstore.migration.legacy.LegacyDataSeeder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 启动播种：旧端样例数据幂等初始化 */
@Component
public class StartupSeeder implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final LegacyDataSeeder seeder;
    private final org.springframework.core.env.Environment env;

    public StartupSeeder(JdbcTemplate jdbc, LegacyDataSeeder seeder,
                         org.springframework.core.env.Environment env) {
        this.jdbc = jdbc;
        this.seeder = seeder;
        this.env = env;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long legacyCount = jdbc.queryForObject("SELECT count(*) FROM legacy_member", Long.class);
        if (legacyCount != null && legacyCount > 0) {
            return;
        }
        if (env.getProperty("app.seed-on-startup", Boolean.class, true)) {
            seeder.reseed();
        }
    }
}
