package com.bookstore.migration.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI bookstoreOpenApi() {
        return new OpenAPI().info(new Info()
                .title("书店会员历史迁移服务 API")
                .version("1.0.0")
                .description("旧端营业不中断：同截点快照、序号变更流、持卡归属人工判定、余额重建、核对下钻与受控切换"));
    }
}
