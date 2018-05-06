package com.walker.nldwd;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import fi.iki.elonen.NanoHTTPD;
import okhttp3.*;
import org.apache.commons.io.IOUtils;
import org.javacord.api.DiscordApi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class TwitchInteraction extends NanoHTTPD {
    private static final String SUBSCRIBE_URL = "https://api.twitch.tv/helix/webhooks/hub";
    private static final String LOOKUP_USER_URL = "https://api.twitch.tv/helix/users?login=";
    private static final String LOOKUP_ID_URL = "https://api.twitch.tv/helix/users?id=";
    private static final String LOOKUP_GAME_ID_URL = "https://api.twitch.tv/helix/gam3es?id=";

    private final String token;
    private final String twitchSecret;
    private final String callbackUrl;
    private final int port;
    private final Database database;
    private final OkHttpClient client;
    private final WebhookInteraction webhookInteraction;

    private Map<Long, CompletableFuture<Void>> pendingSubscribes = new HashMap<>();

    public TwitchInteraction(String token, String twitchSecret, String callbackUrl, int port, Database database, OkHttpClient okHttpClient, WebhookInteraction webhookInteraction) throws IOException {
        super(port);
        this.token = token;
        this.twitchSecret = twitchSecret;
        this.callbackUrl = callbackUrl;
        this.port = port;
        this.database = database;
        this.client = okHttpClient;
        this.webhookInteraction = webhookInteraction;
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    public CompletableFuture<Void> subscribe(long serverId, String channelName) throws Demo.NLException, IOException, SQLException {
        long userID = queryUserId(channelName);

        if(database.isTrackingStream(userID)){
            database.createNewStreamTrack(userID, serverId);
            return CompletableFuture.completedFuture(null);
        } else {
            database.addStreamTrack(userID, serverId);
            Request request = createSubscribeRequest(userID);
            System.out.println("Request: " + request.toString());
            this.client.newCall(request).execute();
            CompletableFuture<Void> cf = new CompletableFuture<>();
            System.out.println("Setting id pending: " + userID);
            pendingSubscribes.put(userID, cf);
            return cf;
        }
    }

    private long queryUserId(String channelName) throws IOException, Demo.NLException {
        Request request = new Request.Builder().get().url(LOOKUP_USER_URL + channelName).header("Client-ID", token).build();
        System.out.println("Request: " + request.toString());

        okhttp3.Response response = this.client.newCall(request).execute();
        String responseString = Objects.requireNonNull(response.body()).string();
        System.out.println("Response: " + responseString);

        JsonObject responseData = Json.parse(responseString).asObject();
        JsonArray responseSegments = responseData.get("data").asArray();
        if(responseSegments.size() > 0){
            return Long.parseLong(responseSegments.get(0).asObject().get("id").asString());
        } else {
            throw new Demo.NLException("Unable to find user by name: " + channelName);
        }
    }

    private String queryUserName(long id) throws IOException, Demo.NLException {
        Request request = new Request.Builder().get().url(LOOKUP_ID_URL + id).header("Client-ID", token).build();
        System.out.println("Request: " + request.toString());

        okhttp3.Response response = this.client.newCall(request).execute();
        String responseString = Objects.requireNonNull(response.body()).string();
        System.out.println("Response: " + responseString);

        JsonObject responseData = Json.parse(responseString).asObject();
        JsonArray responseSegments = responseData.get("data").asArray();
        if(responseSegments.size() > 0){
            return responseSegments.get(0).asObject().get("login").asString();
        } else {
            throw new Demo.NLException("Unable to find user by id: " + id);
        }
    }

    private String queryGameName(long id) throws IOException, Demo.NLException {
        Request request = new Request.Builder().get().url(LOOKUP_GAME_ID_URL + id).header("Client-ID", token).build();
        System.out.println("Request: " + request.toString());

        okhttp3.Response response = this.client.newCall(request).execute();
        String responseString = Objects.requireNonNull(response.body()).string();
        System.out.println("Response: " + responseString);

        JsonObject responseData = Json.parse(responseString).asObject();
        JsonArray responseSegments = responseData.get("data").asArray();
        if(responseSegments.size() > 0){
            return responseSegments.get(0).asObject().get("name").asString();
        } else {
            throw new Demo.NLException("Unable to find game by id: " + id);
        }
    }

    private Request createSubscribeRequest(long twitchID){
        JsonObject data = Json.object()
                .set("hub.callback", callbackUrl + ":" + port)
                .set("hub.mode", "subscribe")
                .set("hub.topic", "https://api.twitch.tv/helix/streams?user_id=" + twitchID)
                .set("hub.lease_seconds", 864000)
                .set("hub.secret", twitchSecret);
        RequestBody requestBody = RequestBody.create(MediaType.parse("application/json"), data.toString());
        return new Request.Builder().url(SUBSCRIBE_URL).header("Client-ID", token).post(requestBody).build();
    }

    @Override
    public Response serve(IHTTPSession session) {
        Method method = session.getMethod();
        System.out.println("Method: " + method.name());
        System.out.println("URI: " + session.getUri());
        System.out.println("Params: " + mapToString(session.getParms()));
        if(method == Method.GET){
            String hubMode = session.getParms().get("hub.mode");
            String hubTopic = session.getParms().get("hub.topic");
            long id = Long.parseLong(getLastOf(hubTopic.split("user_id=")));
            System.out.println("Responding to get. mode=" + hubMode + ", topic=" + hubTopic + ", id=" + id);
            if(hubMode.equalsIgnoreCase("subscribe")){
                System.out.println("Responding to subscribe..");
                if(pendingSubscribes.containsKey(id)){
                    pendingSubscribes.get(id).complete(null);
                    pendingSubscribes.remove(id);
                } else {
                    System.err.println("Pending keys: " + Arrays.toString(pendingSubscribes.keySet().toArray()));
                    System.err.println("Got subscription verify for a non-pending request for id: " + id);
                }
                String challenge = session.getParms().get("hub.challenge");
                return newFixedLengthResponse(challenge);
            } else if(hubMode.equalsIgnoreCase("denied")){
                System.out.println("Responding to denied...");
                if(pendingSubscribes.containsKey(id)){
                    pendingSubscribes.get(id).completeExceptionally(new Demo.NLException("Failed to subscribe: " + session.getParms().get("hub.reason")));
                    pendingSubscribes.remove(id);
                } else {
                    System.err.println("Pending keys: " + Arrays.toString(pendingSubscribes.keySet().toArray()));
                    System.err.println("Got subscription denial for a non-pending request for id: " + id);
                }
                return newFixedLengthResponse("");
            } else {
                System.err.println("Unknown hub.mode: " + hubMode);
            }
        } else if(method == Method.POST){
            try{
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                System.out.println("Post body: " + session.getQueryParameterString());
                JsonArray payloadData = Json.parse(session.getQueryParameterString()).asObject().get("data").asArray();
                if(payloadData.size() > 0){
                    long userId = Long.parseLong(payloadData.get(0).asObject().get("user_id").asString());
                    long gameId = Long.parseLong(payloadData.get(0).asObject().get("game_id").asString());
                    webhookInteraction.notifyStream(queryUserName(userId), userId, queryGameName(gameId),
                            payloadData.get(0).asObject().get("title").asString());
                } else {
                    //TODO: Deletions when the stream is ended.
                }
            } catch (IOException | Demo.NLException | SQLException | ResponseException e) {
                e.printStackTrace();
            }
        } else {
            System.err.println("Unknown method type: " + method.name());
        }
        return newFixedLengthResponse(Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "");
    }

    private <K, V> String mapToString(Map<K, V> map){
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        for(K key : map.keySet()){
            sb.append(key.toString()).append(":").append(map.get(key).toString()).append(",");
        }
        sb.append("}");
        return sb.toString();
    }

    private <T> T getLastOf(T[] array){
        return array[array.length - 1];
    }
}
