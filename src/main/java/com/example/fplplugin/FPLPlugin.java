package com.example.fplplugin;

import org.bukkit.plugin.java.JavaPlugin;

public class FPLPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("FPLPlugin 활성화됨!");
    }

    @Override
    public void onDisable() {
        getLogger().info("FPLPlugin 비활성화됨!");
    }
}

