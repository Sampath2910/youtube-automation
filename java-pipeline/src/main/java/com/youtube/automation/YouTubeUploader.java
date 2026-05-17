package com.youtube.automation;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.DataStore;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoSnippet;
import com.google.api.services.youtube.model.VideoStatus;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

public class YouTubeUploader {

    private static final String APPLICATION_NAME = "YouTube Automation Pipeline";
    private static final String CLIENT_SECRETS_FILE = "client_secrets.json";
    private static final Collection<String> SCOPES = Arrays.asList(
        "https://www.googleapis.com/auth/youtube.upload",
        "https://www.googleapis.com/auth/youtube"
    );

    private YouTube youtube;
    private String clientSecretsPath;

    public YouTubeUploader() {
        this.clientSecretsPath = getClientSecretsPath();
        initializeYouTube();
    }

    private String getClientSecretsPath() {
        Path configPath = Paths.get(Config.getProjectDir(), CLIENT_SECRETS_FILE);
        if (Files.exists(configPath)) {
            return configPath.toString();
        }
        Path defaultPath = Paths.get(System.getProperty("user.dir"), CLIENT_SECRETS_FILE);
        if (Files.exists(defaultPath)) {
            return defaultPath.toString();
        }
        return CLIENT_SECRETS_FILE;
    }

    private void initializeYouTube() {
        try {
            YouTube.Builder builder = new YouTube.Builder(
                new com.google.api.client.http.javanet.NetHttpTransport.Builder().build(),
                GsonFactory.getDefaultInstance(),
                getCredentials()
            ).setApplicationName(APPLICATION_NAME);
            this.youtube = builder.build();
        } catch (Exception e) {
            System.err.println("Warning: Could not initialize YouTube API: " + e.getMessage());
        }
    }

    private Credential getCredentials() throws Exception {
        FileDataStoreFactory dataStoreFactory = new FileDataStoreFactory(
            new File(Paths.get(Config.getProjectDir(), "youtube_credentials").toString())
        );
        DataStore<StoredCredential> datastore = dataStoreFactory.getDataStore("youtube");

        GoogleClientSecrets secrets = GoogleClientSecrets.load(
            GsonFactory.getDefaultInstance(),
            new FileReader(clientSecretsPath)
        );

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
            new com.google.api.client.http.javanet.NetHttpTransport.Builder().build(),
            GsonFactory.getDefaultInstance(),
            secrets,
            SCOPES
        ).setCredentialDataStore(datastore).build();

        Credential credential = flow.loadCredential("user");

        if (credential == null || credential.getAccessToken() == null) {
            if (credential != null && credential.refreshToken()) {
                credential.refreshToken();
            } else {
                String authUrl = flow.newAuthorizationUrl()
                    .setRedirectUri("urn:ietf:wg:oauth:2.0:oob")
                    .build();
                System.out.println("\n=== OAuth Authorization Required ===");
                System.out.println("Open this URL in your browser:");
                System.out.println(authUrl);
                System.out.println("\nEnter the authorization code:");

                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                String code = reader.readLine();

                TokenResponse response = flow.newTokenRequest(code)
                    .setRedirectUri("urn:ietf:wg:oauth:2.0:oob")
                    .execute();
                credential = flow.createAndStoreCredential(response, "user");
            }
        }

        return credential;
    }

    public String uploadVideo(String videoPath, String title, String description, String[] tags) throws Exception {
        if (youtube == null) {
            System.err.println("YouTube API not initialized. Skipping upload.");
            System.out.println("Video is saved at: " + videoPath);
            return videoPath;
        }

        System.out.println("Uploading video to YouTube: " + title);

        File videoFile = new File(videoPath);
        if (!videoFile.exists()) {
            throw new FileNotFoundException("Video file not found: " + videoPath);
        }

        Video video = new Video();
        VideoStatus status = new VideoStatus();
        status.setPrivacyStatus("public");
        status.setSelfDeclaredMadeForKids(false);
        video.setStatus(status);

        VideoSnippet snippet = new VideoSnippet();
        snippet.setTitle(title);
        snippet.setDescription(description);
        snippet.setTags(Arrays.asList(tags));
        snippet.setCategoryId("1");
        video.setSnippet(snippet);

        InputStreamContent mediaContent = new InputStreamContent(
            "video/*",
            new BufferedInputStream(new FileInputStream(videoFile))
        );
        mediaContent.setLength(videoFile.length());

        YouTube.Videos.Insert insert = youtube.videos()
            .insert(Arrays.asList("snippet", "status"), video, mediaContent);

        MediaHttpUploader uploader = insert.getMediaHttpUploader();
        uploader.setDirectUploadEnabled(false);
        uploader.setChunkSize(MediaHttpUploader.MINIMUM_CHUNK_SIZE * 2);
        uploader.setProgressListener(new MediaHttpUploaderProgressListener() {
            @Override
            public void progressChanged(MediaHttpUploader uploader) throws IOException {
                switch (uploader.getUploadState()) {
                    case INITIATION_STARTED:
                        System.out.println("Upload initiation started...");
                        break;
                    case INITIATION_COMPLETE:
                        System.out.println("Upload initiation complete.");
                        break;
                    case MEDIA_IN_PROGRESS:
                        System.out.println("Upload progress: " + (uploader.getProgress() * 100) + "%");
                        break;
                    case MEDIA_COMPLETE:
                        System.out.println("Upload complete!");
                        break;
                    case NOT_STARTED:
                        System.out.println("Upload has not started.");
                        break;
                }
            }
        });

        Video returnedVideo = insert.execute();
        String videoId = returnedVideo.getId();
        String videoUrl = "https://youtu.be/" + videoId;

        System.out.println("\n=== Upload Successful ===");
        System.out.println("Video ID: " + videoId);
        System.out.println("Video URL: " + videoUrl);

        return videoUrl;
    }

    public String uploadWithThumbnail(String videoPath, String title, String description,
                                       String[] tags, String thumbnailPath) throws Exception {
        String videoUrl = uploadVideo(videoPath, title, description, tags);

        if (thumbnailPath != null && Files.exists(Paths.get(thumbnailPath))) {
            try {
                youtube.thumbnails().set(videoUrl.substring(videoUrl.lastIndexOf("/") + 1),
                    new InputStreamContent("image/jpeg",
                        new BufferedInputStream(new FileInputStream(thumbnailPath))))
                    .execute();
                System.out.println("Thumbnail set successfully");
            } catch (Exception e) {
                System.err.println("Could not set thumbnail: " + e.getMessage());
            }
        }

        return videoUrl;
    }
}
