package com.miniscape;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.kit.KitType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Combat Debug",
	description = "Logs combat ticks, positions, hits, and NPC behavior for MiniScape reference",
	tags = {"combat", "debug", "tick", "miniscape"}
)
public class CombatDebugPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private CombatDebugConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private CombatDebugOverlay overlay;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	private CombatDebugPanel panel;
	private NavigationButton navButton;

	private static final int TARGET_WORLD = 393;

	private int tickCount = 0;
	private Actor lastTarget = null;
	private WorldPoint lastPlayerPos = null;
	private WorldPoint lastTargetPos = null;
	private boolean worldSet = false;
	private int lastWeaponId = -2;
	private NPC npcTargetingUs = null;

	// Auto-scan accumulation
	private static final int AUTO_SCAN_INTERVAL = 10; // every 10 ticks (~6s)
	private final Map<Integer, String[]> allNpcs = new LinkedHashMap<>();
	private final Map<Integer, String[]> allObjects = new LinkedHashMap<>();
	private final Map<Integer, String[]> allItems = new LinkedHashMap<>();
	private final Map<String, int[]> allPlayers = new LinkedHashMap<>(); // name -> equipment kit ids
	private final Map<String, int[]> allTileFlags = new LinkedHashMap<>(); // "x,y,plane" -> [flag]
	private final Map<Integer, String> allProjectiles = new LinkedHashMap<>(); // gfxId -> description
	private final Map<Integer, String> allGraphicsObjects = new LinkedHashMap<>(); // gfxId -> description
	private final Map<Integer, String> allAnimations = new LinkedHashMap<>(); // animId -> context
	private final Set<Integer> allSoundEffects = new HashSet<>();
	private final Set<String> allMapRegions = new LinkedHashSet<>(); // "regionId:baseX,baseY"
	private boolean autoScanDirty = false;

	// Scanned chunk tracking (8x8 tile chunks)
	private final Set<Long> scannedChunks = new HashSet<>();

	// File logging
	private PrintWriter logWriter = null;

	// MiniScape sync
	private static final String SYNC_HOST = "localhost";
	private static final int SYNC_PORT = 2223;
	private static final int NEARBY_RANGE = 15;
	private Socket syncSocket = null;
	private OutputStream syncOut = null;
	private boolean syncConnecting = false;

	@Override
	protected void startUp()
	{
		tickCount = 0;
		worldSet = false;
		overlayManager.add(overlay);
		overlay.setScannedChunks(scannedChunks);

		// Load previously scanned chunks
		try
		{
			String home = System.getProperty("user.home");
			File chunkFile = new File(home, ".runelite/scanned-chunks.txt");
			if (chunkFile.exists())
			{
				java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(chunkFile));
				String line;
				while ((line = br.readLine()) != null)
				{
					String[] parts = line.split(",");
					if (parts.length == 2)
					{
						int cx = Integer.parseInt(parts[0].trim());
						int cy = Integer.parseInt(parts[1].trim());
						scannedChunks.add(((long) cx << 32) | (cy & 0xFFFFFFFFL));
					}
				}
				br.close();
				log.info("Loaded " + scannedChunks.size() + " previously scanned chunks");
			}
		}
		catch (Exception e)
		{
			log.warn("Could not load scanned chunks", e);
		}

		panel = new CombatDebugPanel();
		panel.setScanCallback(this::scanNearby);
		BufferedImage icon;
		try
		{
			icon = ImageUtil.loadImageResource(getClass(), "/combat_debug_icon.png");
		}
		catch (Exception e)
		{
			icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		}
		navButton = NavigationButton.builder()
			.tooltip("Combat Debug")
			.icon(icon)
			.priority(10)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		// Open log file
		try
		{
			String home = System.getProperty("user.home");
			File logFile = new File(home, ".runelite/combat-debug.log");
			logWriter = new PrintWriter(new FileWriter(logFile, true), true);
			logWriter.println("=== Session started " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + " ===");
		}
		catch (Exception e)
		{
			log.warn("Could not open combat debug log file", e);
		}

		log.info("Combat Debug started");
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		clientToolbar.removeNavigation(navButton);
		closeSyncSocket();
		if (logWriter != null)
		{
			logWriter.println("=== Session ended ===");
			logWriter.close();
			logWriter = null;
		}
		log.info("Combat Debug stopped");
	}

	private void debugLog(String msg)
	{
		log.info(msg);
		if (panel != null) panel.addLine(msg);
		if (logWriter != null) logWriter.println(msg);
		if (config.chatLog())
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null);
		}
	}

	private String getPlayerCombatStyle()
	{
		// VarPlayer 357 = attack style index (0-3)
		int attackStyleIdx = client.getVarpValue(VarPlayer.ATTACK_STYLE);
		Player player = client.getLocalPlayer();
		if (player == null) return "unknown";

		PlayerComposition comp = player.getPlayerComposition();
		if (comp == null) return "unknown";

		int weaponId = comp.getEquipmentId(KitType.WEAPON);
		if (weaponId <= 0) return "melee(unarmed)";

		// Check animation to determine range/mage/melee
		int anim = player.getAnimation();
		if (anim == -1) return "idle(wep=" + weaponId + ")";

		// Common ranged animations
		if (anim == 426 || anim == 1074 || anim == 7552 || anim == 7555 || // bows
			anim == 5061 || anim == 9964 || // blowpipe, bofa
			anim == 7618 || anim == 8194 || // chinchompas, crossbows
			anim == 4230 || anim == 7554) // crossbow, msb
		{
			return "ranged(anim=" + anim + ",wep=" + weaponId + ")";
		}

		// Common magic animations
		if (anim == 711 || anim == 708 || anim == 727 || anim == 1162 || // standard spells
			anim == 1979 || anim == 7855 || // barrage, sang
			anim == 393 || anim == 1167 || anim == 1978 || anim == 1576) // ancients, lunars
		{
			return "mage(anim=" + anim + ",wep=" + weaponId + ")";
		}

		// Assume melee otherwise
		return "melee(anim=" + anim + ",style=" + attackStyleIdx + ",wep=" + weaponId + ")";
	}

	private String getWeaponName(int weaponId)
	{
		if (weaponId <= 0) return "unarmed";
		ItemComposition item = client.getItemDefinition(weaponId);
		if (item != null && item.getName() != null && !item.getName().equals("null"))
		{
			return item.getName() + "(" + weaponId + ")";
		}
		return "wep=" + weaponId;
	}

	private String getNpcCombatStyle(NPC npc)
	{
		int anim = npc.getAnimation();
		if (anim == -1) return "idle";
		// We log the raw animation so we can classify later
		return "anim=" + anim;
	}

	private void connectSync()
	{
		if (syncSocket != null || syncConnecting) return;
		syncConnecting = true;
		new Thread(() ->
		{
			try
			{
				Socket socket = new Socket(SYNC_HOST, SYNC_PORT);
				// WebSocket handshake — key must be 16 random bytes base64-encoded
				byte[] keyBytes = new byte[16];
				new java.util.Random().nextBytes(keyBytes);
				String key = Base64.getEncoder().encodeToString(keyBytes);
				String handshake = "GET / HTTP/1.1\r\n" +
					"Host: " + SYNC_HOST + ":" + SYNC_PORT + "\r\n" +
					"Upgrade: websocket\r\n" +
					"Connection: Upgrade\r\n" +
					"Sec-WebSocket-Key: " + key + "\r\n" +
					"Sec-WebSocket-Version: 13\r\n\r\n";
				OutputStream out = socket.getOutputStream();
				out.write(handshake.getBytes(StandardCharsets.UTF_8));
				out.flush();

				// Read response (just consume the HTTP response headers)
				InputStream in = socket.getInputStream();
				StringBuilder resp = new StringBuilder();
				int prev = 0;
				while (true)
				{
					int b = in.read();
					if (b == -1) throw new IOException("Connection closed during handshake");
					resp.append((char) b);
					if (prev == '\n' && b == '\r')
					{
						int next = in.read();
						if (next == '\n') break; // \r\n\r\n
					}
					prev = b;
				}

				if (!resp.toString().contains("101"))
				{
					socket.close();
					throw new IOException("WebSocket handshake failed: " + resp);
				}

				syncSocket = socket;
				syncOut = out;
				log.info("MiniScape sync connected");
			}
			catch (Exception e)
			{
				log.debug("MiniScape sync connect failed: {}", e.getMessage());
			}
			finally
			{
				syncConnecting = false;
			}
		}, "miniscape-sync").start();
	}

	private void sendWsFrame(String text)
	{
		try
		{
			byte[] payload = text.getBytes(StandardCharsets.UTF_8);
			int len = payload.length;
			ByteArrayOutputStream frame = new ByteArrayOutputStream();
			frame.write(0x81); // text frame, fin

			// Masked frame (client must mask)
			byte[] mask = {0x12, 0x34, 0x56, 0x78};
			if (len < 126)
			{
				frame.write(0x80 | len); // masked + length
			}
			else if (len < 65536)
			{
				frame.write(0x80 | 126);
				frame.write((len >> 8) & 0xFF);
				frame.write(len & 0xFF);
			}
			else
			{
				frame.write(0x80 | 127);
				for (int i = 7; i >= 0; i--)
				{
					frame.write((int) ((len >> (8 * i)) & 0xFF));
				}
			}
			frame.write(mask);
			for (int i = 0; i < payload.length; i++)
			{
				frame.write(payload[i] ^ mask[i % 4]);
			}
			syncOut.write(frame.toByteArray());
			syncOut.flush();
		}
		catch (Exception e)
		{
			closeSyncSocket();
		}
	}

	private void closeSyncSocket()
	{
		try
		{
			if (syncSocket != null) syncSocket.close();
		}
		catch (Exception ignored) {}
		syncSocket = null;
		syncOut = null;
	}

	private void sendSync()
	{
		if (syncSocket == null || syncOut == null) return;
		Player player = client.getLocalPlayer();
		if (player == null) return;

		WorldPoint pos = player.getWorldLocation();
		int pAnim = player.getAnimation();

		StringBuilder sb = new StringBuilder();
		sb.append("{\"t\":\"sync\",\"tick\":").append(tickCount);
		sb.append(",\"player\":{\"x\":").append(pos.getX());
		sb.append(",\"y\":").append(pos.getY());
		sb.append(",\"anim\":").append(pAnim);
		sb.append(",\"hp\":").append(client.getBoostedSkillLevel(Skill.HITPOINTS));
		sb.append(",\"maxHp\":").append(client.getRealSkillLevel(Skill.HITPOINTS));

		// Combat stats
		sb.append(",\"attack\":").append(client.getRealSkillLevel(Skill.ATTACK));
		sb.append(",\"strength\":").append(client.getRealSkillLevel(Skill.STRENGTH));
		sb.append(",\"defence\":").append(client.getRealSkillLevel(Skill.DEFENCE));
		sb.append(",\"ranged\":").append(client.getRealSkillLevel(Skill.RANGED));
		sb.append(",\"magic\":").append(client.getRealSkillLevel(Skill.MAGIC));
		sb.append(",\"prayer\":").append(client.getRealSkillLevel(Skill.PRAYER));

		// Equipment
		PlayerComposition syncComp = player.getPlayerComposition();
		if (syncComp != null)
		{
			sb.append(",\"equipment\":{");
			sb.append("\"head\":").append(syncComp.getEquipmentId(KitType.HEAD));
			sb.append(",\"cape\":").append(syncComp.getEquipmentId(KitType.CAPE));
			sb.append(",\"neck\":").append(syncComp.getEquipmentId(KitType.AMULET));
			sb.append(",\"weapon\":").append(syncComp.getEquipmentId(KitType.WEAPON));
			sb.append(",\"body\":").append(syncComp.getEquipmentId(KitType.TORSO));
			sb.append(",\"shield\":").append(syncComp.getEquipmentId(KitType.SHIELD));
			sb.append(",\"legs\":").append(syncComp.getEquipmentId(KitType.LEGS));
			sb.append(",\"hands\":").append(syncComp.getEquipmentId(KitType.HANDS));
			sb.append(",\"feet\":").append(syncComp.getEquipmentId(KitType.BOOTS));
			sb.append("}");
		}

		Actor target = player.getInteracting();
		if (target instanceof NPC)
		{
			sb.append(",\"targetIdx\":").append(((NPC) target).getIndex());
		}
		sb.append("}");

		sb.append(",\"npcs\":[");
		boolean first = true;
		for (NPC npc : client.getNpcs())
		{
			WorldPoint npcPos = npc.getWorldLocation();
			int dx = Math.abs(pos.getX() - npcPos.getX());
			int dy = Math.abs(pos.getY() - npcPos.getY());
			if (dx > NEARBY_RANGE || dy > NEARBY_RANGE) continue;

			if (!first) sb.append(",");
			first = false;
			sb.append("{\"idx\":").append(npc.getIndex());
			sb.append(",\"id\":").append(npc.getId());
			sb.append(",\"name\":\"").append(npc.getName() != null ? npc.getName().replace("\"", "") : "?");
			sb.append("\",\"x\":").append(npcPos.getX());
			sb.append(",\"y\":").append(npcPos.getY());
			sb.append(",\"anim\":").append(npc.getAnimation());
			sb.append(",\"hp\":").append(npc.getHealthRatio());
			sb.append(",\"maxHp\":").append(npc.getHealthScale());

			Actor npcTarget = npc.getInteracting();
			if (npcTarget == player)
			{
				sb.append(",\"targetPlayer\":true");
			}
			sb.append("}");
		}
		sb.append("]}");

		sendWsFrame(sb.toString());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (!worldSet && event.getGameState() == GameState.LOGIN_SCREEN)
		{
			World world = client.createWorld();
			world.setId(TARGET_WORLD);
			world.setActivity("Combat Debug");
			world.setAddress("oldschool" + TARGET_WORLD + ".runescape.com");
			world.setTypes(java.util.EnumSet.of(net.runelite.api.WorldType.MEMBERS));
			client.changeWorld(world);
			worldSet = true;
			log.info("Auto-selected world " + TARGET_WORLD);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tickCount++;
		Player player = client.getLocalPlayer();
		if (player == null) return;

		WorldPoint playerPos = player.getWorldLocation();
		Actor target = player.getInteracting();

		// Detect weapon switches
		PlayerComposition comp = player.getPlayerComposition();
		if (comp != null)
		{
			int weaponId = comp.getEquipmentId(KitType.WEAPON);
			if (weaponId != lastWeaponId && lastWeaponId != -2)
			{
				String oldName = getWeaponName(lastWeaponId);
				String newName = getWeaponName(weaponId);
				debugLog(String.format("[%d] SWITCH: %s → %s", tickCount, oldName, newName));
			}
			lastWeaponId = weaponId;
		}

		// Log every tick while in combat
		if (target instanceof NPC)
		{
			NPC npc = (NPC) target;
			WorldPoint npcPos = npc.getWorldLocation();
			int dx = Math.abs(playerPos.getX() - npcPos.getX());
			int dy = Math.abs(playerPos.getY() - npcPos.getY());
			boolean cardinalAdj = (dx == 1 && dy == 0) || (dx == 0 && dy == 1);
			boolean sameTile = dx == 0 && dy == 0;

			int dist = Math.max(dx, dy); // Chebyshev distance
			String pStyle = getPlayerCombatStyle();
			String nStyle = getNpcCombatStyle(npc);

			String msg = String.format("[%d] cb: P:%d,%d N:%d,%d dist=%d adj=%s pStyle=%s nStyle=%s hp=%d/%d",
				tickCount,
				playerPos.getX(), playerPos.getY(),
				npcPos.getX(), npcPos.getY(),
				dist,
				cardinalAdj || sameTile,
				pStyle, nStyle,
				npc.getHealthRatio(), npc.getHealthScale()
			);
			debugLog(msg);
		}

		// Detect target change
		if (target != lastTarget)
		{
			if (target instanceof NPC)
			{
				NPC npc = (NPC) target;
				String pStyle = getPlayerCombatStyle();
				String msg = String.format("[%d] TARGET: %s (id=%d) at %s pStyle=%s",
					tickCount, npc.getName(), npc.getId(), npc.getWorldLocation(), pStyle);
				debugLog(msg);
			}
			else if (target == null && lastTarget != null)
			{
				String msg = String.format("[%d] TARGET: none (disengaged)", tickCount);
				debugLog(msg);
			}
			lastTarget = target;
		}

		// Track position changes for movement logging
		if (lastPlayerPos != null && !lastPlayerPos.equals(playerPos) && target instanceof NPC)
		{
			String msg = String.format("[%d] MOVE: P:%d,%d → P:%d,%d",
				tickCount,
				lastPlayerPos.getX(), lastPlayerPos.getY(),
				playerPos.getX(), playerPos.getY());
			debugLog(msg);
		}

		lastPlayerPos = playerPos;
		lastTargetPos = target != null ? target.getWorldLocation() : null;

		// Auto-scan nearby entities every N ticks
		if (tickCount % AUTO_SCAN_INTERVAL == 0)
		{
			autoScanNearby();
		}

		// MiniScape sync — try connecting every 10 ticks if not connected
		if (syncSocket == null && tickCount % 10 == 0)
		{
			connectSync();
		}
		sendSync();
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		Player player = client.getLocalPlayer();
		if (player == null) return;

		Actor actor = event.getActor();
		Hitsplat hitsplat = event.getHitsplat();
		WorldPoint playerPos = player.getWorldLocation();

		if (actor == player && hitsplat.isMine())
		{
			// Player got hit — check interacting first, fall back to NPC targeting us
			Actor attacker = player.getInteracting();
			if (!(attacker instanceof NPC) && npcTargetingUs != null)
			{
				attacker = npcTargetingUs;
			}
			String attackerInfo = "unknown";
			String attackerPos = "?,?";
			String attackerStyle = "?";
			if (attacker instanceof NPC)
			{
				NPC aNpc = (NPC) attacker;
				attackerInfo = aNpc.getName();
				WorldPoint ap = aNpc.getWorldLocation();
				attackerPos = ap.getX() + "," + ap.getY();
				attackerStyle = getNpcCombatStyle(aNpc);
			}
			String msg = String.format("[%d] HIT_RECV: %s hits you for %d (type=%d) P:%d,%d A:%s aStyle=%s",
				tickCount, attackerInfo, hitsplat.getAmount(), hitsplat.getHitsplatType(),
				playerPos.getX(), playerPos.getY(), attackerPos, attackerStyle);
			debugLog(msg);
		}
		else if (actor instanceof NPC && hitsplat.isMine())
		{
			// NPC got hit by us only
			NPC npc = (NPC) actor;
			WorldPoint npcPos = npc.getWorldLocation();
			String pStyle = getPlayerCombatStyle();
			String msg = String.format("[%d] HIT_DEAL: You hit %s for %d (type=%d) pStyle=%s P:%d,%d N:%d,%d",
				tickCount, npc.getName(), hitsplat.getAmount(), hitsplat.getHitsplatType(),
				pStyle, playerPos.getX(), playerPos.getY(), npcPos.getX(), npcPos.getY());
			debugLog(msg);
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		Player player = client.getLocalPlayer();
		if (player == null) return;

		Actor actor = event.getActor();

		if (actor == player)
		{
			int anim = player.getAnimation();
			if (anim == -1) return;
			if (player.getInteracting() instanceof NPC)
			{
				debugLog(String.format("[%d] P_ANIM: %d", tickCount, anim));
			}
			else
			{
				// Non-combat animation (skilling, emotes, etc.)
				WorldPoint pos = player.getWorldLocation();
				debugLog(String.format("[%d] SKILL_ANIM: %d at P:%d,%d", tickCount, anim, pos.getX(), pos.getY()));
			}
		}
		else if (actor instanceof NPC && actor == player.getInteracting())
		{
			NPC npc = (NPC) actor;
			String msg = String.format("[%d] N_ANIM: %s → %d", tickCount, npc.getName(), npc.getAnimation());
			debugLog(msg);
		}
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		Player player = client.getLocalPlayer();
		if (player == null) return;

		// NPC started/stopped targeting us
		if (event.getSource() instanceof NPC && event.getTarget() == player)
		{
			NPC npc = (NPC) event.getSource();
			npcTargetingUs = npc;
			WorldPoint npcPos = npc.getWorldLocation();
			String msg = String.format("[%d] NPC_AGGRO: %s (id=%d) targets you at N:%d,%d",
				tickCount, npc.getName(), npc.getId(), npcPos.getX(), npcPos.getY());
			debugLog(msg);
		}
		else if (event.getSource() instanceof NPC && event.getTarget() == null)
		{
			NPC npc = (NPC) event.getSource();
			if (npc == npcTargetingUs)
			{
				npcTargetingUs = null;
			}
			if (npc == lastTarget)
			{
				String msg = String.format("[%d] NPC_DEAGGRO: %s disengages", tickCount, npc.getName());
				debugLog(msg);
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		// Capture NPC dialogue, game messages — but NOT our own debug messages
		ChatMessageType type = event.getType();
		String text = event.getMessage();
		if (text != null && text.startsWith("[")) return; // skip our own logs

		if (type == ChatMessageType.DIALOG ||
			type == ChatMessageType.MESBOX ||
			type == ChatMessageType.SPAM ||
			type == ChatMessageType.GAMEMESSAGE ||
			type == ChatMessageType.ENGINE)
		{
			String clean = text != null ? text.replaceAll("<[^>]+>", "") : "";
			String msg = String.format("[%d] CHAT(%s): %s", tickCount, type.name(), clean);
			// Only log to panel and file, NOT to game chat (prevents recursion)
			log.info(msg);
			if (panel != null) panel.addLine(msg);
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		String msg = String.format("[%d] XP: %s lvl=%d xp=%d (+%d)",
			tickCount, event.getSkill().getName(), event.getLevel(),
			event.getXp(), event.getXp()); // total XP shown
		debugLog(msg);
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		// Inventory = 93, Equipment = 94
		int containerId = event.getContainerId();
		if (containerId == 93 || containerId == 94)
		{
			Item[] items = event.getItemContainer().getItems();
			StringBuilder sb = new StringBuilder();
			sb.append(String.format("[%d] %s:", tickCount, containerId == 93 ? "INV" : "EQUIP"));
			int count = 0;
			for (Item item : items)
			{
				if (item.getId() > 0)
				{
					int itemId = item.getId();
					ItemComposition def = client.getItemDefinition(itemId);
					String name = def != null ? def.getName() : "?";
					sb.append(String.format(" %s(%d)x%d", name, itemId, item.getQuantity()));
					count++;

					// Track item for export
					if (!allItems.containsKey(itemId) && def != null && name != null && !name.equals("null"))
					{
						allItems.put(itemId, new String[]{name});
						debugLog(String.format("[SCAN] ITEM: %s (id=%d)", name, itemId));
						autoScanDirty = true;
					}
				}
			}
			if (count > 0) debugLog(sb.toString());
			if (autoScanDirty) writeAccumulatedJson();
		}
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		Projectile proj = event.getProjectile();
		int id = proj.getId();
		if (!allProjectiles.containsKey(id))
		{
			allProjectiles.put(id, String.format("startCycle=%d,endCycle=%d,slope=%d,startHeight=%d,endHeight=%d",
				proj.getStartCycle(), proj.getEndCycle(), proj.getSlope(),
				proj.getStartHeight(), proj.getEndHeight()));
			debugLog(String.format("[SCAN] PROJECTILE: id=%d slope=%d heights=%d→%d", id, proj.getSlope(), proj.getStartHeight(), proj.getEndHeight()));
			autoScanDirty = true;
		}
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated event)
	{
		GraphicsObject gfx = event.getGraphicsObject();
		int id = gfx.getId();
		if (!allGraphicsObjects.containsKey(id))
		{
			LocalPoint lp = gfx.getLocation();
			allGraphicsObjects.put(id, String.format("startCycle=%d,level=%d", gfx.getStartCycle(), gfx.getLevel()));
			debugLog(String.format("[SCAN] GFX: id=%d level=%d", id, gfx.getLevel()));
			autoScanDirty = true;
		}
	}

	@Subscribe
	public void onSoundEffectPlayed(SoundEffectPlayed event)
	{
		int id = event.getSoundId();
		if (!allSoundEffects.contains(id))
		{
			allSoundEffects.add(id);
			debugLog(String.format("[SCAN] SOUND: id=%d delay=%d", id, event.getDelay()));
			autoScanDirty = true;
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = event.getMenuOption();
		String target = event.getMenuTarget().replaceAll("<[^>]+>", "");
		int actionParam = event.getParam0();
		int widgetId = event.getParam1();
		MenuAction action = event.getMenuAction();

		debugLog(String.format("[%d] ACTION: %s → %s (type=%s id=%d widget=%d)",
			tickCount, option, target, action.name(), actionParam, widgetId));
	}

	// ── Auto-scan (runs on game thread from onGameTick) ─────────────────────────
	private void autoScanNearby()
	{
		Player player = client.getLocalPlayer();
		if (player == null) return;

		// Track player's walked chunks (for overlay — where player has physically been)
		WorldPoint playerPos = player.getWorldLocation();
		long playerChunk = ((long)(playerPos.getX() / 8) << 32) | ((playerPos.getY() / 8) & 0xFFFFFFFFL);
		scannedChunks.add(playerChunk);

		int newNpcs = 0, newObjs = 0;

		// ── NPCs (scan ALL loaded, no distance filter) ──
		for (NPC npc : client.getNpcs())
		{
			int npcId = npc.getId();
			if (allNpcs.containsKey(npcId)) continue;

			NPCComposition comp = npc.getTransformedComposition();
			if (comp == null) comp = npc.getComposition();
			int[] models = comp != null ? comp.getModels() : new int[0];
			String name = npc.getName() != null ? npc.getName() : "null";
			int combat = comp != null ? comp.getCombatLevel() : -1;
			int size = comp != null ? comp.getSize() : 1;

			StringBuilder modelStr = new StringBuilder("[");
			for (int i = 0; i < models.length; i++)
			{
				if (i > 0) modelStr.append(",");
				modelStr.append(models[i]);
			}
			modelStr.append("]");

			allNpcs.put(npcId, new String[]{name, modelStr.toString(), String.valueOf(combat), String.valueOf(size)});
			debugLog(String.format("[SCAN] NPC: %s (id=%d) combat=%d size=%d models=%s", name, npcId, combat, size, modelStr));
			newNpcs++;
		}

		// ── Scene Objects + chunk tracking ──
		try
		{
			WorldView wv = client.getTopLevelWorldView();
			Scene scene = wv.getScene();
			Tile[][][] tiles = scene.getTiles();
			int plane = wv.getPlane();

			for (int x = 0; x < Constants.SCENE_SIZE; x++)
			{
				for (int y = 0; y < Constants.SCENE_SIZE; y++)
				{
					Tile tile = tiles[plane][x][y];
					if (tile == null) continue;

					for (GameObject go : tile.getGameObjects())
					{
						if (go == null) continue;
						if (scanObject(go.getId(), "gameobj", allObjects)) newObjs++;
					}

					WallObject wo = tile.getWallObject();
					if (wo != null && scanObject(wo.getId(), "wall", allObjects)) newObjs++;

					GroundObject gro = tile.getGroundObject();
					if (gro != null && scanObject(gro.getId(), "ground", allObjects)) newObjs++;

					DecorativeObject deco = tile.getDecorativeObject();
					if (deco != null && scanObject(deco.getId(), "decor", allObjects)) newObjs++;
				}
			}
		}
		catch (Exception e)
		{
			// Scene might not be ready
		}

		// ── Players (other players' appearances) ──
		int newPlayers = 0;
		try
		{
			for (Player p : client.getPlayers())
			{
				if (p == null || p == client.getLocalPlayer()) continue;
				String pName = p.getName();
				if (pName == null || allPlayers.containsKey(pName)) continue;

				PlayerComposition pComp = p.getPlayerComposition();
				if (pComp == null) continue;

				int[] kit = new int[]{
					pComp.getEquipmentId(KitType.HEAD),
					pComp.getEquipmentId(KitType.CAPE),
					pComp.getEquipmentId(KitType.AMULET),
					pComp.getEquipmentId(KitType.WEAPON),
					pComp.getEquipmentId(KitType.TORSO),
					pComp.getEquipmentId(KitType.SHIELD),
					pComp.getEquipmentId(KitType.LEGS),
					pComp.getEquipmentId(KitType.HANDS),
					pComp.getEquipmentId(KitType.BOOTS)
				};
				allPlayers.put(pName, kit);
				debugLog(String.format("[SCAN] PLAYER: %s combat=%d", pName, p.getCombatLevel()));
				newPlayers++;

				// Also track their equipment as items
				for (int itemId : kit)
				{
					if (itemId > 0 && !allItems.containsKey(itemId))
					{
						ItemComposition itemDef = client.getItemDefinition(itemId);
						if (itemDef != null && itemDef.getName() != null && !itemDef.getName().equals("null"))
						{
							allItems.put(itemId, new String[]{itemDef.getName()});
						}
					}
				}
			}
		}
		catch (Exception e) {}

		// ── Collision / Tile flags ──
		int newFlags = 0;
		try
		{
			WorldView wv3 = client.getTopLevelWorldView();
			CollisionData[] collisionData = wv3.getCollisionMaps();
			int plane3 = wv3.getPlane();
			if (collisionData != null && collisionData.length > plane3 && collisionData[plane3] != null)
			{
				int[][] flags = collisionData[plane3].getFlags();
				Scene scene3 = wv3.getScene();
				Tile[][][] tiles3 = scene3.getTiles();
				for (int sx = 0; sx < flags.length; sx++)
				{
					for (int sy = 0; sy < flags[sx].length; sy++)
					{
						int flag = flags[sx][sy];
						if (flag == 0) continue; // empty tile, skip

						Tile t = (sx < Constants.SCENE_SIZE && sy < Constants.SCENE_SIZE) ? tiles3[plane3][sx][sy] : null;
						if (t == null) continue;
						WorldPoint twp = t.getWorldLocation();
						String tileKey = twp.getX() + "," + twp.getY() + "," + plane3;
						if (!allTileFlags.containsKey(tileKey))
						{
							allTileFlags.put(tileKey, new int[]{twp.getX(), twp.getY(), plane3, flag});
							newFlags++;
						}
					}
				}
			}
		}
		catch (Exception e) {}

		// ── Map regions ──
		try
		{
			int[] regions = client.getMapRegions();
			if (regions != null)
			{
				WorldView wv4 = client.getTopLevelWorldView();
				int baseX = wv4.getBaseX();
				int baseY = wv4.getBaseY();
				for (int regionId : regions)
				{
					String regionKey = regionId + ":" + baseX + "," + baseY;
					if (allMapRegions.add(regionKey))
					{
						debugLog(String.format("[SCAN] REGION: %d base=%d,%d", regionId, baseX, baseY));
					}
				}
			}
		}
		catch (Exception e) {}

		// Ground items
		int newItems = 0;
		try
		{
			WorldView wv2 = client.getTopLevelWorldView();
			Tile[][][] tiles2 = wv2.getScene().getTiles();
			int plane2 = wv2.getPlane();
			for (int x = 0; x < Constants.SCENE_SIZE; x++)
			{
				for (int y = 0; y < Constants.SCENE_SIZE; y++)
				{
					Tile tile = tiles2[plane2][x][y];
					if (tile == null) continue;
					java.util.List<TileItem> groundItems = tile.getGroundItems();
					if (groundItems == null) continue;
					for (TileItem gi : groundItems)
					{
						int itemId = gi.getId();
						if (allItems.containsKey(itemId)) continue;
						ItemComposition def = client.getItemDefinition(itemId);
						if (def == null) continue;
						String iname = def.getName();
						if (iname == null || iname.equals("null")) continue;
						allItems.put(itemId, new String[]{iname});
						debugLog(String.format("[SCAN] ITEM: %s (id=%d) [ground]", iname, itemId));
						newItems++;
					}
				}
			}
		}
		catch (Exception e) {}

		if (newNpcs > 0 || newObjs > 0 || newItems > 0 || newPlayers > 0 || newFlags > 0 || autoScanDirty)
		{
			autoScanDirty = true;
			debugLog(String.format("[SCAN] +%d NPCs, +%d objs, +%d items, +%d players, +%d tiles (total: %d/%d/%d/%d, %d tiles, %d chunks, %d regions)",
				newNpcs, newObjs, newItems, newPlayers, newFlags,
				allNpcs.size(), allObjects.size(), allItems.size(), allPlayers.size(),
				allTileFlags.size(), scannedChunks.size(), allMapRegions.size()));
			writeAccumulatedJson();
		}
	}

	// ── Manual scan button (just forces a write) ─────────────────────────────────
	public void scanNearby()
	{
		clientThread.invokeLater(() ->
		{
			autoScanNearby();
			writeAccumulatedJson();
			debugLog(String.format("[SCAN] Manual scan done — %d NPCs, %d objects total", allNpcs.size(), allObjects.size()));
		});
	}

	private void writeAccumulatedJson()
	{
		StringBuilder json = new StringBuilder();
		json.append("{\n  \"npcs\": [\n");
		int i = 0;
		for (Map.Entry<Integer, String[]> entry : allNpcs.entrySet())
		{
			if (i > 0) json.append(",\n");
			String[] v = entry.getValue();
			json.append("    {\"id\":").append(entry.getKey());
			json.append(",\"name\":\"").append(v[0].replace("\"", "\\\""));
			json.append("\",\"models\":").append(v[1]);
			json.append(",\"combat\":").append(v[2]);
			json.append(",\"size\":").append(v[3]).append("}");
			i++;
		}
		json.append("\n  ],");

		json.append("\n  \"objects\": [\n");
		i = 0;
		for (Map.Entry<Integer, String[]> entry : allObjects.entrySet())
		{
			if (i > 0) json.append(",\n");
			String[] v = entry.getValue();
			json.append("    {\"id\":").append(entry.getKey());
			json.append(",\"name\":\"").append(v[0].replace("\"", "\\\""));
			json.append("\",\"actions\":").append(v[1]);
			json.append(",\"type\":\"").append(v[2]);
			json.append("\",\"sizeX\":").append(v[3]);
			json.append(",\"sizeY\":").append(v[4]).append("}");
			i++;
		}
		json.append("\n  ],");

		json.append("\n  \"items\": [\n");
		i = 0;
		for (Map.Entry<Integer, String[]> entry : allItems.entrySet())
		{
			if (i > 0) json.append(",\n");
			String[] v = entry.getValue();
			json.append("    {\"id\":").append(entry.getKey());
			json.append(",\"name\":\"").append(v[0].replace("\"", "\\\"")).append("\"}");
			i++;
		}
		json.append("\n  ],");

		// Players
		json.append("\n  \"players\": [\n");
		i = 0;
		for (Map.Entry<String, int[]> entry : allPlayers.entrySet())
		{
			if (i > 0) json.append(",\n");
			int[] kit = entry.getValue();
			json.append("    {\"name\":\"").append(entry.getKey().replace("\"", "\\\""));
			json.append("\",\"head\":").append(kit[0]);
			json.append(",\"cape\":").append(kit[1]);
			json.append(",\"neck\":").append(kit[2]);
			json.append(",\"weapon\":").append(kit[3]);
			json.append(",\"body\":").append(kit[4]);
			json.append(",\"shield\":").append(kit[5]);
			json.append(",\"legs\":").append(kit[6]);
			json.append(",\"hands\":").append(kit[7]);
			json.append(",\"feet\":").append(kit[8]).append("}");
			i++;
		}
		json.append("\n  ],");

		// Projectiles
		json.append("\n  \"projectiles\": [\n");
		i = 0;
		for (Map.Entry<Integer, String> entry : allProjectiles.entrySet())
		{
			if (i > 0) json.append(",\n");
			json.append("    {\"id\":").append(entry.getKey());
			json.append(",\"info\":\"").append(entry.getValue()).append("\"}");
			i++;
		}
		json.append("\n  ],");

		// Graphics objects
		json.append("\n  \"graphicsObjects\": [\n");
		i = 0;
		for (Map.Entry<Integer, String> entry : allGraphicsObjects.entrySet())
		{
			if (i > 0) json.append(",\n");
			json.append("    {\"id\":").append(entry.getKey());
			json.append(",\"info\":\"").append(entry.getValue()).append("\"}");
			i++;
		}
		json.append("\n  ],");

		// Sound effects
		json.append("\n  \"sounds\": [");
		i = 0;
		for (int soundId : allSoundEffects)
		{
			if (i > 0) json.append(",");
			json.append(soundId);
			i++;
		}
		json.append("],");

		// Map regions
		json.append("\n  \"regions\": [");
		i = 0;
		for (String region : allMapRegions)
		{
			if (i > 0) json.append(",");
			json.append("\"").append(region).append("\"");
			i++;
		}
		json.append("]");

		json.append("\n}");

		// Write main scan file
		try
		{
			String home = System.getProperty("user.home");
			File outFile = new File(home, ".runelite/nearby-models.json");
			PrintWriter pw = new PrintWriter(new FileWriter(outFile, false));
			pw.print(json.toString());
			pw.close();
		}
		catch (Exception e)
		{
			debugLog("[SCAN] Write failed: " + e.getMessage());
		}

		// Write collision data to separate file (can be large)
		if (!allTileFlags.isEmpty())
		{
			try
			{
				String home = System.getProperty("user.home");
				File collFile = new File(home, ".runelite/collision-data.json");
				PrintWriter cpw = new PrintWriter(new FileWriter(collFile, false));
				cpw.print("{\"tiles\":[\n");
				int ci = 0;
				for (Map.Entry<String, int[]> entry : allTileFlags.entrySet())
				{
					if (ci > 0) cpw.print(",\n");
					int[] v = entry.getValue();
					cpw.print(String.format("[%d,%d,%d,%d]", v[0], v[1], v[2], v[3]));
					ci++;
				}
				cpw.print("\n]}");
				cpw.close();
			}
			catch (Exception e)
			{
				debugLog("[SCAN] Collision write failed: " + e.getMessage());
			}
		}

		autoScanDirty = false;

		// Persist scanned chunks
		try
		{
			String home = System.getProperty("user.home");
			File chunkFile = new File(home, ".runelite/scanned-chunks.txt");
			PrintWriter cpw = new PrintWriter(new FileWriter(chunkFile, false));
			for (long chunk : scannedChunks)
			{
				int cx = (int)(chunk >> 32);
				int cy = (int)(chunk & 0xFFFFFFFFL);
				cpw.println(cx + "," + cy);
			}
			cpw.close();
		}
		catch (Exception e) {}
	}

	private boolean scanObject(int objectId, String objType, Map<Integer, String[]> objMap)
	{
		if (objMap.containsKey(objectId)) return false;

		ObjectComposition comp = client.getObjectDefinition(objectId);
		if (comp == null) return false;

		// Handle transformed objects (e.g. ores that change appearance)
		if (comp.getImpostorIds() != null)
		{
			ObjectComposition transformed = comp.getImpostor();
			if (transformed != null) comp = transformed;
		}

		String name = comp.getName();
		if (name == null || name.equals("null") || name.isEmpty()) return false; // skip unnamed

		String[] actions = comp.getActions();
		StringBuilder actStr = new StringBuilder("[");
		if (actions != null)
		{
			boolean first = true;
			for (String a : actions)
			{
				if (a == null) continue;
				if (!first) actStr.append(",");
				actStr.append("\"").append(a.replace("\"", "\\\"")).append("\"");
				first = false;
			}
		}
		actStr.append("]");

		objMap.put(objectId, new String[]{name, actStr.toString(), objType,
			String.valueOf(comp.getSizeX()), String.valueOf(comp.getSizeY())});
		debugLog(String.format("[SCAN] OBJ: %s (id=%d) type=%s size=%dx%d", name, objectId, objType, comp.getSizeX(), comp.getSizeY()));
		return true;
	}

	@Provides
	CombatDebugConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CombatDebugConfig.class);
	}
}
