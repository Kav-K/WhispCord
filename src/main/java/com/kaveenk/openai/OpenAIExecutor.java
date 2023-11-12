package com.kaveenk.openai;

import com.kaveenk.util.EnvReader;
import okhttp3.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class OpenAIExecutor {

    private String OPENAI_API_KEY = null;

    public OpenAIExecutor() {
        EnvReader envReader = EnvReader.getInstance();
        try {
            this.OPENAI_API_KEY = envReader.getEnv("OPENAI_API_KEY");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public String transcribeAudio(File audioFile) throws IOException {
        OkHttpClient client = new OkHttpClient();
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.getName(),
                        RequestBody.create(MediaType.parse("audio/wav"), audioFile))
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("language", "en")
                .addFormDataPart("response_format", "text")
                .build();

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer " + this.OPENAI_API_KEY)
                .header("Content-Type", "multipart/form-data")
                .post(requestBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

            assert response.body() != null;

            return response.body().string();
        }

    }
}
