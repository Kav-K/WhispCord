package com.kaveenk.discord;

import com.kaveenk.openai.OpenAIExecutor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.audio.*;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;


import java.io.*;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import ws.schild.jave.*;
import okhttp3.*;
import java.io.File;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.util.concurrent.LinkedBlockingQueue;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

public class Bot extends ListenerAdapter {
    private static final float VOLUME = 1.0f; // Modify as needed
    private BlockingQueue<byte[]> receivedBytes = new LinkedBlockingQueue<>();
    private Guild currentGuild;

    private final OpenAIExecutor openaiExecutor = new OpenAIExecutor();

    private final JDA jda; // Here is your JDA instance

    private volatile boolean isTranscribing = false;

    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, List<byte[]>> userBuffers = new ConcurrentHashMap<>();


    private ConcurrentHashMap<String, ConcurrentLinkedQueue<byte[]>> userAudioData = new ConcurrentHashMap<>();


    public Bot(String botToken) throws Exception {
        jda = JDABuilder.createDefault(botToken)
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .setActivity(Activity.playing("WhispCord"))
                .addEventListeners(this)
                .build();

        jda.awaitReady();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        final JDA jda = this.jda;  // Make a final local variable
        var message = event.getMessage();
        var content = message.getContentRaw();
        var guild = event.getGuild();

        if (content.equals("!join")) {
            Member member = event.getMember();
            VoiceChannel voiceChannel = null;

            if (member != null) {
                voiceChannel = (VoiceChannel) member.getVoiceState().getChannel();
            }

            if (voiceChannel != null) {
                var audioManager = guild.getAudioManager();
                audioManager.openAudioConnection(voiceChannel);
                // Mute the bot upon joining
                new java.util.Timer().schedule(
                        new java.util.TimerTask() {
                            @Override
                            public void run() {
                                guild.getSelfMember().mute(true).queue();
                            }
                        },
                        500
                );
                audioManager.setReceivingHandler(new AudioReceiveHandler() {

                    private long bytesCollected = 0;
                    private static final int X_SECONDS = 10;  // how many seconds of audio
                    private AudioFormat format = new AudioFormat(48000, 16, 2, true, true);

                    @Override
                    public boolean canReceiveCombined() {
                        return true;
                    }

                    @Override
                    public boolean canReceiveUser() {
                        return true;
                    }

                    @Override
                    public void handleCombinedAudio(CombinedAudio combinedAudio) {
                        byte[] audioData = combinedAudio.getAudioData(VOLUME);
                        if (isSilence(audioData)) {
                            return;
                        }
                        receivedBytes.add(audioData);
                        bytesCollected += audioData.length;

                        // If enough data for X seconds of audio has been collected
                        if (bytesCollected >= X_SECONDS * format.getFrameSize() * format.getFrameRate()) {
                            bytesCollected = 0;
                        }
                    }
                    @Override
                    public void handleUserAudio(UserAudio userAudio) {
                        String userId = userAudio.getUser().getId();
                        byte[] audioData = userAudio.getAudioData(VOLUME);
                        if (isSilence(audioData)) {
                            return;
                        }
                        userBuffers.computeIfAbsent(userId, k -> new ArrayList<>()).add(userAudio.getAudioData(VOLUME));

                        Timer timer = timers.get(userId);
                        if (timer != null) {
                            timer.cancel();
                        }

                        timer = new Timer();
                        timer.schedule(new TimerTask() {
                            @Override
                            public void run() {
                                try {
                                    // Convert the buffered audio data to a .wav file
                                    byte[] decodedData = getDecodedData(userBuffers.get(userId));
                                    File audioFile = getWavFile(getNextFile(userId), decodedData, userId);

                                    // Transcribe the audio
                                    String transcript = openaiExecutor.transcribeAudio(audioFile);
                                    System.out.println("Transcript: " + transcript);

                                    // Send transcript to Discord channel
                                    User user = jda.retrieveUserById(userId).complete();
                                    if (transcript.trim().isEmpty()) {
                                        return;
                                    }

                                    var channel = message.getChannel();
                                    channel.sendMessage(user.getName() + ": " + transcript).queue();

                                    // Reset the buffer
                                    userBuffers.put(userId, new ArrayList<>());
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }, 1 * 500); // 30 seconds

                        timers.put(userId, timer);
                    }
                });
                currentGuild = guild;
            }
        }


        if (content.equals("!leave")) {
            if (currentGuild != null) {
                currentGuild.getAudioManager().closeAudioConnection();
                timers.values().forEach(Timer::cancel);
                timers.clear();
                userBuffers.clear();
            }
            isTranscribing = false;
        }

    }

    private File getNextFile(String userId) {
        String fileName = "output_" + userId + ".wav";
        return new File(fileName);
    }

    private byte[] getDecodedData(List<byte[]> audioData) throws IOException {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        for (byte[] bs : audioData) {
            byteStream.write(bs, 0, bs.length);
        }
        return byteStream.toByteArray();
    }



    private File getWavFile(File outFile, byte[] decodedData, String userId) throws IOException, EncoderException {
        final JDA jda = this.jda;  // Make a final local variable
        AudioFormat format = new AudioFormat(48000, 16, 2, true, true);
        AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(decodedData), format, decodedData.length / format.getFrameSize());
        AudioSystem.write(stream, AudioFileFormat.Type.WAVE, outFile);

        return outFile;
    }

    public static boolean isSilence(byte[] audioData) {
        double sum = 0.0;

        // Iterate over pairs of bytes to compute 16-bit signed PCM values
        for (int i = 0; i < audioData.length; i += 2) {
            // Convert the bytes to a 16-bit signed value
            int audioSample = (audioData[i+1] << 8) | (audioData[i] & 0xFF);
            sum += audioSample * audioSample;  // square and accumulate
        }

        double rms = Math.sqrt(sum / (audioData.length / 2));

        // Adjust this threshold based on your requirements
        double silenceThreshold = 1000.0;  // Example value, adjust as needed

        return rms < silenceThreshold;
    }



}