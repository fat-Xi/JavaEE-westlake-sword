package com.zjut.westlakesword;

import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final Auth auth;

  public WebConfig(Auth auth) {
    this.auth = auth;
  }

  public void addInterceptors(InterceptorRegistry registry) {
    registry
      .addInterceptor(
        new HandlerInterceptor() {
          public boolean preHandle(
            HttpServletRequest req,
            HttpServletResponse res,
            Object handler
          ) throws Exception {
            String uri = req.getRequestURI();
            if (
              !java.util.Set.of("/api/login", "/api/register").contains(uri) &&
              (req.getSession(false) == null ||
                req.getSession(false).getAttribute("uid") == null)
            ) {
              res.sendError(401, "请先登录");
              return false;
            }
            // Vue 为写请求携带自定义头。跨站表单无法发送此头，且后端不开放跨域。
            if (
              !java.util.Set.of("GET", "HEAD", "OPTIONS").contains(
                req.getMethod()
              ) &&
              !"1".equals(req.getHeader("X-App-Request"))
            ) {
              res.sendError(403, "缺少请求校验头");
              return false;
            }
            // 工作人员和展商的接口范围也隔离，不能通过手工请求进入参会者或管理业务。
            if (
              !java.util.Set.of(
                "/api/login",
                "/api/register",
                "/api/logout",
                "/api/me",
                "/api/profile",
                "/api/password",
                "/api/notes",
                "/api/notes/read"
              ).contains(uri)
            ) {
              auth.active(req.getSession());
              String role = auth.role(req.getSession());
              boolean read = "GET".equals(req.getMethod());
              if ("STAFF".equals(role)) {
                boolean allowed =
                  (read &&
                    (uri.equals("/api/events") ||
                      uri.startsWith("/api/meetings") ||
                      uri.equals("/api/checkins") ||
                      uri.startsWith("/api/attendance"))) ||
                  ("POST".equals(req.getMethod()) &&
                    (uri.equals("/api/checkin") ||
                      uri.equals("/api/attendance/scan")));
                if (!allowed) {
                  res.sendError(403, "工作人员仅可查看会议信息和处理签到");
                  return false;
                }
              }
              if ("EXHIBITOR".equals(role)) {
                boolean allowed =
                  uri.startsWith("/api/requests") ||
                  (read &&
                    (uri.equals("/api/events") ||
                      uri.equals("/api/posts/mine"))) ||
                  ("POST".equals(req.getMethod()) &&
                    uri.equals("/api/posts")) ||
                  ("PUT".equals(req.getMethod()) &&
                    uri.matches("/api/posts/[0-9]+"));
                if (!allowed) {
                  res.sendError(403, "展商仅可管理自己的办会申请和展示资料");
                  return false;
                }
              }
            }
            return true;
          }
        }
      )
      .addPathPatterns("/api/**");
  }
}
