package com.hmdp.config;

import com.hmdp.intercepter.LoginInterceptor;
import com.hmdp.intercepter.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

@Resource
private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //登陆拦截器
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);
        registry.addInterceptor(new LoginInterceptor()).excludePathPatterns("/user/code"
                , "/user/login"
                , "/blog/hot"
                , "/shop/**"
                , "/shop-type/**"
                , "/upload/**"
                , "/voucher/**"
        ).order(1);

    }
}