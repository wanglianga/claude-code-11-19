package com.bookstore.migration.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 统一 JdbcTemplate 行映射：SQL 时间类型一律转 java.time，
 * 全系统 queryForList 拿到的 Map 中时间列均为 LocalDateTime/LocalDate/LocalTime。
 */
@Configuration
public class JdbcConfig {

    static class TemporalColumnMapRowMapper extends ColumnMapRowMapper {
        @Override
        protected Object getColumnValue(ResultSet rs, int index) throws SQLException {
            Object value = super.getColumnValue(rs, index);
            if (value instanceof Timestamp timestamp) {
                return timestamp.toLocalDateTime();
            }
            if (value instanceof java.sql.Date date) {
                return date.toLocalDate();
            }
            if (value instanceof Time time) {
                return time.toLocalTime();
            }
            return value;
        }

        @Override
        protected String getColumnKey(String columnName) {
            return columnName.toLowerCase();
        }

        @Override
        public java.util.Map<String, Object> mapRow(ResultSet rs, int rowNum) throws SQLException {
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            java.util.Map<String, Object> mapOfColumnValues = createColumnMap(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                String key = getColumnKey(lookupColumnName(metaData, i));
                mapOfColumnValues.putIfAbsent(key, getColumnValue(rs, i));
            }
            return mapOfColumnValues;
        }

        private String lookupColumnName(ResultSetMetaData metaData, int index) throws SQLException {
            String name = metaData.getColumnLabel(index);
            return (name == null || name.isEmpty()) ? metaData.getColumnName(index) : name;
        }
    }

    static class TemporalJdbcTemplate extends JdbcTemplate {
        TemporalJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        protected ColumnMapRowMapper getColumnMapRowMapper() {
            return new TemporalColumnMapRowMapper();
        }
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new TemporalJdbcTemplate(dataSource);
    }
}
