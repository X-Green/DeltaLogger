package com.github.fabricservertools.deltalogger;

import com.github.fabricservertools.deltalogger.command.Commands;
import com.github.fabricservertools.deltalogger.dao.RegistryDAO;
import com.github.fabricservertools.deltalogger.gql.ApiServer;
import com.github.fabricservertools.deltalogger.listeners.EntityEventListener;
import com.github.fabricservertools.deltalogger.listeners.PlayerEventListener;
import com.github.fabricservertools.deltalogger.network.InspectPacket;
import com.github.fabricservertools.deltalogger.network.SearchPacket;
import com.google.common.collect.Sets;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.registry.Registry;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class ModInit implements DedicatedServerModInitializer {
	private static DatabaseManager dm;
	public static Properties CONFIG;
	public static Thread dmThread;
	private static ApiServer apiServer; //= new ApiServer();

	public static void loadConfig(Path configPath) {
		Properties props = new Properties();

		if (!configPath.toFile().exists()) {
			// Create default config
			try {
				Files.copy(ModInit.class.getResourceAsStream("/data/deltalogger/default_config.properties"), configPath);
				DeltaLogger.LOG.info("Optional configuration for DeltaLogger created in `config` directory. Using SQLite by default.");
			} catch (IOException e1) {
				e1.printStackTrace();
			}
		}

		try {
			props.load(new FileInputStream(configPath.toString()));
		} catch (IOException e) {
			e.printStackTrace();
		}

		CONFIG = props;
	}

	private void onServerSetup(MinecraftServer server) {
		File dataBaseSavePath;
		String dbSavePathStr = CONFIG.getProperty("db_save_path", "");
		String dbSaveAbsPathStr = CONFIG.getProperty("db_save_path_absolute", "");
		try {
		    if (!dbSaveAbsPathStr.equals("")) {
			dataBaseSavePath = new File(dbSaveAbsPathStr);
		    } else if (!dbSavePathStr.equals("")){
			dataBaseSavePath = new File(server.getRunDirectory(), dbSavePathStr);
		    } else {
			dataBaseSavePath = server.getSavePath(WorldSavePath.ROOT).toFile();
		    }
		    dataBaseSavePath.mkdirs();
		} catch (Exception e) {
		    dataBaseSavePath = server.getSavePath(WorldSavePath.ROOT).toFile();
		}
		dm = DatabaseManager.create(dataBaseSavePath);
		//dm = DatabaseManager.create(server.getSavePath(WorldSavePath.ROOT).toFile());
		String portString = CONFIG.getProperty("webapp_port", "8080");
		try {
			if(CONFIG.getProperty("use_webapp", "true").equals("true")) {
				apiServer = new ApiServer();
				apiServer.start(Integer.parseInt(portString));
			} else {
				DeltaLogger.LOG.warn("WebUI disabled in config, skipping");
			}
		} catch (NumberFormatException | NullPointerException e) {
			throw new RuntimeException("invalid port number: " + portString);
		}
		if (CONFIG.getProperty("enable_networking", "false").equals("true")) {
			SearchPacket.registerServer();
			InspectPacket.registerServer();
		}
		new PlayerEventListener();
		new EntityEventListener(!CONFIG.getProperty("log_entities_without_names", "false").equals("true"));
	}

	private void onServerStart(MinecraftServer server) {
		List<Set<Identifier>> idSets = new ArrayList<Set<Identifier>>() {{
			add(Registry.BLOCK.getIds());
			add(Registry.ITEM.getIds());
			add(Registry.ENTITY_TYPE.getIds());
			add(server.getSaveProperties().getGeneratorOptions().getDimensions().getIds());
		}};

		Set<Identifier> ids = idSets.stream().reduce(Sets::union).orElse(new HashSet<>());

		ids.forEach(id -> dm.queueOp(RegistryDAO.insert(id)));

		ModInit.dmThread = new Thread(dm);
		ModInit.dmThread.start();
	}

	private void onStop(MinecraftServer server) {
		if(CONFIG.getProperty("use_webapp", "true").equals("true")) {
			apiServer.stop();
		}
		dm.stop();
		try {
			dmThread.join();
		} catch (InterruptedException e) {
			DeltaLogger.LOG.warn("Interrupted while writing to database");
		}
	}

	@Override
	public void onInitializeServer() {
		loadConfig(Paths.get(FabricLoader.getInstance().getConfigDir().toString(), "deltalogger.properties"));

		ServerLifecycleEvents.SERVER_STARTING.register(this::onServerSetup);
		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStart);
		ServerLifecycleEvents.SERVER_STOPPED.register(this::onStop);

		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> Commands.register(dispatcher));
	}
}
