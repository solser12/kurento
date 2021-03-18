/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.kurento.tutorial.one2manycall;

import org.kurento.client.KurentoClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Video call 1 to N demo (main).
 *
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @since 5.0.0
 */

// KurentoClient는 Spring Bean으로 인스턴스화
// Bean은 애플리케이션에 미디어 기능을 추가하는데 사용되는 Kurento Media Pipelines 를 만드는 데 사용
// localhost에서 8888 포트에서 대기하는 Kurento Media Server와 연결하기 위해 WebSocket 사용
@SpringBootApplication
@EnableWebSocket
public class One2ManyCallApp implements WebSocketConfigurer {

  @Bean
  public CallHandler callHandler() {
    return new CallHandler();
  }

  @Bean
  public KurentoClient kurentoClient() {
    return KurentoClient.create();
  }

  @Bean
  public ServletServerContainerFactoryBean createServletServerContainerFactoryBean() {
    ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
    container.setMaxTextMessageBufferSize(32768);
    return container;
  }

  // WebSocket를 등록 (wss:// + localhost + /call)
  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(callHandler(), "/call");
  }

  public static void main(String[] args) throws Exception {
    SpringApplication.run(One2ManyCallApp.class, args);
  }

}