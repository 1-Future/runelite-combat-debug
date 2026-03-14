package com.openscape;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.io.*;
import java.util.*;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.kit.KitType;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Combat Logger",
	description = "Logs tick-by-tick combat data for OpenScape logic validation",
	tags = {"combat", "logger", "tick", "openscape"}
)
public class CombatDebugPlugin extends Plugin
{
	@Inject private Client client;
	@Inject private CombatDebugConfig config;

	private static final String LOG_FILE = System.getProperty("user.home") + "/.runelite/combat-log.json";

	// Combat session state
	private boolean inCombat = false;
	private int combatStartTick = 0;
	private int tickCount = 0;
	private Actor currentTarget = null;
	private List<Map<String, Object>> currentSession = new ArrayList<>();
	private List<Map<String, Object>> allSessions = new ArrayList<>();

	@Provides
	CombatDebugConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CombatDebugConfig.class);
	}

	@Override
	protected void startUp()
	{
		tickCount = 0;
		inCombat = false;
		log.info("Combat Logger started — log: " + LOG_FILE);
	}

	@Override
	protected void shutDown()
	{
		if (inCombat) endCombat();
		log.info("Combat Logger stopped");
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tickCount++;
		Player player = client.getLocalPlayer();
		if (player == null) return;

		Actor target = player.getInteracting();

		// Detect combat start
		if (!inCombat && target instanceof NPC)
		{
			inCombat = true;
			combatStartTick = tickCount;
			currentTarget = target;
			currentSession = new ArrayList<>();
			log.info("[CombatLogger] Combat started vs " + ((NPC) target).getName() + " tick=" + tickCount);
		}

		// Detect combat end
		if (inCombat && (target == null || !(target instanceof NPC) || ((NPC)target).isDead()))
		{
			endCombat();
			return;
		}

		if (!inCombat) return;

		// Log this tick
		NPC npc = (NPC) currentTarget;
		WorldPoint playerPos = player.getWorldLocation();
		WorldPoint npcPos = npc.getWorldLocation();

		Map<String, Object> tick = new LinkedHashMap<>();
		tick.put("tick", tickCount - combatStartTick);
		tick.put("gameTick", tickCount);

		// Player data
		Map<String, Object> playerData = new LinkedHashMap<>();
		playerData.put("x", playerPos.getX());
		playerData.put("y", playerPos.getY());
		playerData.put("plane", playerPos.getPlane());
		playerData.put("animation", player.getAnimation());
		playerData.put("combatStyle", getPlayerCombatStyle());
		playerData.put("combatType", getPlayerCombatType());
		playerData.put("hp", client.getBoostedSkillLevel(Skill.HITPOINTS));
		playerData.put("maxHp", client.getRealSkillLevel(Skill.HITPOINTS));
		tick.put("player", playerData);

		// NPC data
		Map<String, Object> npcData = new LinkedHashMap<>();
		npcData.put("name", npc.getName());
		npcData.put("id", npc.getId());
		npcData.put("x", npcPos.getX());
		npcData.put("y", npcPos.getY());
		npcData.put("plane", npcPos.getPlane());
		npcData.put("animation", npc.getAnimation());
		npcData.put("combatType", getNpcCombatType(npc));
		npcData.put("hp", npc.getHealthRatio());
		npcData.put("hpScale", npc.getHealthScale());
		tick.put("npc", npcData);

		currentSession.add(tick);
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (!inCombat) return;
		Actor actor = event.getActor();
		Hitsplat hit = event.getHitsplat();
		Player player = client.getLocalPlayer();
		if (player == null) return;

		// Find the last tick entry and attach hit to it
		if (!currentSession.isEmpty())
		{
			Map<String, Object> lastTick = currentSession.get(currentSession.size() - 1);
			List<Map<String, Object>> hits = (List<Map<String, Object>>) lastTick.computeIfAbsent("hits", k -> new ArrayList<>());

			Map<String, Object> hitData = new LinkedHashMap<>();
			hitData.put("target", actor == player ? "player" : "npc");
			hitData.put("amount", hit.getAmount());
			hitData.put("type", hit.getHitsplatType());
			hitData.put("mine", hit.isMine());
			hits.add(hitData);
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		if (!inCombat) return;
		if (event.getNpc() == currentTarget)
		{
			// NPC died
			if (!currentSession.isEmpty())
			{
				Map<String, Object> lastTick = currentSession.get(currentSession.size() - 1);
				lastTick.put("npcDied", true);
			}
			endCombat();
		}
	}

	private void endCombat()
	{
		if (!inCombat) return;
		inCombat = false;
		log.info("[CombatLogger] Combat ended — " + currentSession.size() + " ticks logged");

		Map<String, Object> session = new LinkedHashMap<>();
		session.put("npc", currentTarget != null ? ((NPC)currentTarget).getName() : "unknown");
		session.put("npcId", currentTarget instanceof NPC ? ((NPC)currentTarget).getId() : -1);
		session.put("totalTicks", currentSession.size());
		session.put("timestamp", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
		session.put("ticks", currentSession);

		allSessions.add(session);
		currentTarget = null;
		currentSession = new ArrayList<>();

		writeLog();
	}

	private void writeLog()
	{
		try
		{
			Gson gson = new Gson();
			String json = gson.toJson(allSessions);
			try (FileWriter fw = new FileWriter(LOG_FILE))
			{
				fw.write(json);
			}
			log.info("[CombatLogger] Written to " + LOG_FILE);
		}
		catch (Exception e)
		{
			log.warn("[CombatLogger] Failed to write log", e);
		}
	}

	private String getPlayerCombatStyle()
	{
		int style = client.getVarpValue(VarPlayer.ATTACK_STYLE);
		Player player = client.getLocalPlayer();
		if (player == null) return "unknown";
		PlayerComposition comp = player.getPlayerComposition();
		if (comp == null) return "unknown";
		int weapon = comp.getEquipmentId(KitType.WEAPON);
		// Basic style detection from varp
		switch (style)
		{
			case 0: return "accurate";
			case 1: return "aggressive";
			case 2: return "defensive";
			case 3: return "controlled";
			default: return "unknown";
		}
	}

	private String getPlayerCombatType()
	{
		Player player = client.getLocalPlayer();
		if (player == null) return "unknown";
		int anim = player.getAnimation();
		// Rough type detection by animation range
		// Melee anims are generally < 10000, ranged/magic higher
		if (anim == -1) return "idle";
		// Check if player has bow/staff equipped
		PlayerComposition comp = player.getPlayerComposition();
		if (comp != null)
		{
			int weapon = comp.getEquipmentId(KitType.WEAPON);
			// Could expand this with weapon ID lookup
		}
		return "melee"; // default, expand as needed
	}

	private String getNpcCombatType(NPC npc)
	{
		int anim = npc.getAnimation();
		// Basic heuristic — expand with known NPC animation IDs
		if (anim == -1) return "idle";
		return "melee"; // expand as needed
	}
}
