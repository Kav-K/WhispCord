package com.kaveenk;

import com.kaveenk.discord.Bot;
import com.kaveenk.util.EnvReader;

public class Main {
    public static void main(String[] args) throws Exception {
        Bot bot = new Bot(EnvReader.getInstance().getEnv("DISCORD_TOKEN"));
    }
}