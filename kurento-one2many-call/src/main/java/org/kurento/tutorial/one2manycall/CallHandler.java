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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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

//  private final ConcurrentHashMap<String, UserSession> viewers = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, UserSession> presenters = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ConcurrentHashMap<String, UserSession>> rooms = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, MediaPipeline> pipelines = new ConcurrentHashMap<>();

  @Autowired
  private KurentoClient kurento;

//  private MediaPipeline pipeline;
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

        // presenter 꺼내기
        UserSession presenterUserSession = presenters.get(session.getId());

        UserSession user = null;
        if (presenterUserSession != null) {
          if (presenterUserSession.getSession() == session) {
            user = presenterUserSession;
          } else {
            BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
            System.out.print("joinId : ");
            String presenterId = br.readLine();
            ConcurrentHashMap<String, UserSession> viewers = rooms.get(presenterId);
            user = viewers.get(session.getId());
            br.close();
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

    // presenter를 저장
    presenters.put(session.getId(), presenterUserSession);
    // pipeline 저장
    pipelines.put(session.getId(), pipeline);
    // 시청자를 저장하기 위한 해시맵
    ConcurrentHashMap<String, UserSession> viewers = new ConcurrentHashMap<>();
    // 방을 만들어 시청자를 저장
    rooms.put(session.getId(), viewers);


    log.info("=============== TOTAL SESSION (" + presenters.size() + ") ================");
    for (Map.Entry<String, UserSession> entry : presenters.entrySet()) {
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

  /* viewer
   * =================================================
   */
  private synchronized void viewer(final WebSocketSession session, JsonObject jsonMessage)
      throws IOException {
    // joinId 추출
    String joinId = jsonMessage.get("joinId").getAsString();

    // 참가하고 싶은 presenterSession 추출
    UserSession presenterUserSession = presenters.get(joinId);

    // 보려는 presenter가 없을 때
    if (presenterUserSession == null || presenterUserSession.getWebRtcEndpoint() == null) {
      JsonObject response = new JsonObject();
      response.addProperty("id", "viewerResponse");
      response.addProperty("response", "rejected");
      response.addProperty("message",
              "No active sender now. Become sender or . Try again later ...");
      session.sendMessage(new TextMessage(response.toString()));
    } else {
      ConcurrentHashMap<String, UserSession> viewers = rooms.get(presenterUserSession.getSession().getId());
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

      // 현재 viewer UserSession 생성
      UserSession viewer = new UserSession(session);
      // 지금 viewer를 viewers에 저장
      viewers.put(session.getId(), viewer);

      // presenter의 파이프라인을 가져오기
      MediaPipeline pipeline = presenterUserSession.getWebRtcEndpoint().getMediaPipeline();
      // viewer와 webRtcEndPoint를 만들어 준다.
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
      presenterUserSession.getWebRtcEndpoint().connect(nextWebRtc);
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
    BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
    // joinId 추출
    System.out.print("joinId : ");
    String joinId = br.readLine();//jsonMessage.get("joinId").getAsString();
    // 내 세션
    String myId = session.getId();

    if (joinId != null) {
      // 본인이 presenter인 경우
      if (joinId.equals("presenter") && presenters.containsKey(myId)) {
        // 내가 세션 꺼내기
        UserSession presenterUserSession = presenters.get(joinId);
        // 방 꺼내기
        ConcurrentHashMap<String, UserSession> viewers = rooms.get(myId);

        for (UserSession viewer : viewers.values()) {
          JsonObject response = new JsonObject();
          response.addProperty("id", "stopCommunication");
          viewer.sendMessage(response);
        }

        log.info("Releasing media pipeline");
        MediaPipeline pipeline = pipelines.get(myId);
        if (pipeline != null) {
          pipeline.release();
        }
        pipeline = null;
        presenterUserSession = null;

      } else {
        // 내가 시청중인 presenter 추출
        UserSession presenterUserSession = presenters.get(joinId);

        // presenter가 있으면 그 방에서 나가기
        if (presenterUserSession != null && presenterUserSession.getSession().getId().equals(joinId)) {

          // 방 꺼내기
          ConcurrentHashMap<String, UserSession> viewers = rooms.get(presenterUserSession.getSession().getId());

          // 방에 내 세션이 있으면
          if (viewers != null && viewers.containsKey(myId)) {
            if (viewers.get(myId).getWebRtcEndpoint() != null) {
              viewers.get(myId).getWebRtcEndpoint().release();
            }
            viewers.remove(myId);
          }
        }
      }
    }
    br.close();
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
