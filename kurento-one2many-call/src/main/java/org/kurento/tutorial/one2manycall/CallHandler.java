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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.kurento.client.*;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protocol handler for 1 to N video call communication.
 *
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @since 5.0.0
 */
// TextWebSocketHandler에서 WebSocket 요청을 처리하도록 구현
public class CallHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(CallHandler.class);
  private static final Gson gson = new GsonBuilder().create();

  private final ConcurrentHashMap<String, UserSession> viewers = new ConcurrentHashMap<>();

  @Autowired
  private KurentoClient kurento;

//  private MediaPipeline pipeline;
  private HashMap<String, UserSession> userSessionHashMap = new HashMap<>();
//  private UserSession presenterUserSession;


  /* handleTextMessage
   * =================================================
   * 소켓에다 메세지 보낼 때 사용
   */
  @Override
  public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);
    log.debug("Incoming message from session '{}': {}", session.getId(), jsonMessage);

    // id가 무엇인지 구분
    switch (jsonMessage.get("id").getAsString()) {
      case "presenter": // presenter면 kurento에게 미디어 파이프라인
        log.info("handleTextMessage - send data : presenter");
        try {
          // presenter로 이동
          presenter(session, jsonMessage);
        } catch (Throwable t) {
          handleErrorResponse(t, session, "presenterResponse");
        }
        break;
      case "viewer":
        try {
          log.info("handleTextMessage - send data : viewer");
          viewer(session, jsonMessage);
        } catch (Throwable t) {
          handleErrorResponse(t, session, "viewerResponse");
        }
        break;
      case "onIceCandidate": {
        log.info("handleTextMessage - send data : onIceCandidate");
        JsonObject candidate = jsonMessage.get("candidate").getAsJsonObject();

        UserSession user = null;
        if (presenterUserSession != null) {
          if (presenterUserSession.getSession() == session) {
            user = presenterUserSession;
          } else {
            user = viewers.get(session.getId());
          }
        }
        if (user != null) {
          IceCandidate cand =
              new IceCandidate(candidate.get("candidate").getAsString(), candidate.get("sdpMid")
                  .getAsString(), candidate.get("sdpMLineIndex").getAsInt());
          user.addCandidate(cand);
        }
        break;
      }
      case "stop":
        log.info("handleTextMessage - send data : stop");
        stop(session);
        break;
      default:
        break;
    }
  }


  /* handleErrorResponse
   * =================================================
   */
  private void handleErrorResponse(Throwable throwable, WebSocketSession session, String responseId)
      throws IOException {
    stop(session);
    log.error(throwable.getMessage(), throwable);
    JsonObject response = new JsonObject();
    response.addProperty("id", responseId);
    response.addProperty("response", "rejected");
    response.addProperty("message", throwable.getMessage());
    session.sendMessage(new TextMessage(response.toString()));
  }


  /* presenter
   * =================================================
   */
  private synchronized void presenter(final WebSocketSession session, JsonObject jsonMessage)
      throws IOException {
//    if (presenterUserSession == null) {
      // UserSession 생성
      log.info("presenter - new UserSession : {}", session.getId());
      UserSession presenterUserSession = new UserSession(session);

      // 파이프 생성
      log.info("presenter - Create Media Pipeline");
      MediaPipeline pipeline = kurento.createMediaPipeline();

      // userRTCEndpoint을 써줘야 dataChannel을 이용할 수 있다.
      presenterUserSession.setWebRtcEndpoint(new WebRtcEndpoint.Builder(pipeline).useDataChannels().build());
//      presenterUserSession.setWebRtcEndpoint(new WebRtcEndpoint.Builder(pipeline).build());

      WebRtcEndpoint presenterWebRtc = presenterUserSession.getWebRtcEndpoint();

      // 실제 Presenter와 Kurento가 생성한 미디어 파이프라인 사이에 연결
      // addIceCandidateFoundListener를 사용하여 Candidate를 찾는 과정이 이루여 짐
      presenterWebRtc.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

        @Override
        public void onEvent(IceCandidateFoundEvent event) {
          JsonObject response = new JsonObject();
          response.addProperty("id", "iceCandidate");
          response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
          try {
            synchronized (session) {
              session.sendMessage(new TextMessage(response.toString()));
            }
          } catch (IOException e) {
            log.debug(e.getMessage());
          }
        }
      });

      String sdpOffer = jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString();
      String sdpAnswer = presenterWebRtc.processOffer(sdpOffer);

      JsonObject response = new JsonObject();
      response.addProperty("id", "presenterResponse");
      response.addProperty("response", "accepted");
      response.addProperty("sdpAnswer", sdpAnswer);

      synchronized (session) {
        presenterUserSession.sendMessage(response);
      }
      presenterWebRtc.gatherCandidates();

      userSessionHashMap.put(presenterUserSession.getSession().getId(), presenterUserSession);
      log.info("=============== TOTAL SESSION (" + userSessionHashMap.size() + ") ================");
      for (Map.Entry<String, UserSession> entry : userSessionHashMap.entrySet()) {
        log.info("session Id : {}", entry.getKey());
      }
      log.info("======================================================");
//    } else {
//      // UserSession이 이미 생성 되어 있으면
//      JsonObject response = new JsonObject();
//      response.addProperty("id", "presenterResponse");
//      response.addProperty("response", "rejected");
//      response.addProperty("message",
//          "Another user is currently acting as sender. Try again later ...");
//      session.sendMessage(new TextMessage(response.toString()));
//    }
  }

  /* viwer
   * =================================================
   */
  private synchronized void viewer(final WebSocketSession session, JsonObject jsonMessage)
      throws IOException {
    String joinId = jsonMessage.get("joinId").getAsString();
    UserSession userSession = userSessionHashMap.get(joinId);

    // 원하는 방이 없음
    if (userSession == null || userSession.getWebRtcEndpoint() == null) {
      JsonObject response = new JsonObject();
      response.addProperty("id", "viewerResponse");
      response.addProperty("response", "rejected");
      response.addProperty("message",
          "No active sender now. Become sender or . Try again later ...");
      session.sendMessage(new TextMessage(response.toString()));
    } else {
      // 이미 접속중인 경우
      if (viewers.containsKey(session.getId())) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "viewerResponse");
        response.addProperty("response", "rejected");
        response.addProperty("message", "You are already viewing in this session. "
            + "Use a different browser to add additional viewers.");
        session.sendMessage(new TextMessage(response.toString()));
        return;
      }
      UserSession viewer = new UserSession(session);
      viewers.put(session.getId(), viewer);

      MediaPipeline pipeline = userSession.getWebRtcEndpoint().getMediaPipeline();
      // 이미 만들어진 파이프라인을 가져와서 webRtcEndPoint를 만들어 준다.
      WebRtcEndpoint nextWebRtc = new WebRtcEndpoint.Builder(pipeline).useDataChannels().build();
//      WebRtcEndpoint nextWebRtc = new WebRtcEndpoint.Builder(pipeline).build();

      nextWebRtc.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

        @Override
        public void onEvent(IceCandidateFoundEvent event) {
          JsonObject response = new JsonObject();
          response.addProperty("id", "iceCandidate");
          response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
          try {
            synchronized (session) {
              session.sendMessage(new TextMessage(response.toString()));
            }
          } catch (IOException e) {
            log.debug(e.getMessage());
          }
        }
      });

      viewer.setWebRtcEndpoint(nextWebRtc);
      userSession.getWebRtcEndpoint().connect(nextWebRtc);
      String sdpOffer = jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString();
      String sdpAnswer = nextWebRtc.processOffer(sdpOffer);

      JsonObject response = new JsonObject();
      response.addProperty("id", "viewerResponse");
      response.addProperty("response", "accepted");
      response.addProperty("sdpAnswer", sdpAnswer);

      synchronized (session) {
        viewer.sendMessage(response);
      }
      nextWebRtc.gatherCandidates();
    }
  }


  /* stop
   * =================================================
   */
  private synchronized void stop(WebSocketSession session) throws IOException {
    String sessionId = session.getId();
    if (presenterUserSession != null && presenterUserSession.getSession().getId().equals(sessionId)) {
      for (UserSession viewer : viewers.values()) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "stopCommunication");
        viewer.sendMessage(response);
      }

      log.info("Releasing media pipeline");
      if (pipeline != null) {
        pipeline.release();
      }
      pipeline = null;
      presenterUserSession = null;
    } else if (viewers.containsKey(sessionId)) {
      if (viewers.get(sessionId).getWebRtcEndpoint() != null) {
        viewers.get(sessionId).getWebRtcEndpoint().release();
      }
      viewers.remove(sessionId);
    }
  }

  /* afterConnectionClosed
   * =================================================
   * Connection이 Close 됐을 때
   */
  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    stop(session);
  }

}
