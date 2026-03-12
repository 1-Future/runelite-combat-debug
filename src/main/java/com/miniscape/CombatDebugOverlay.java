package com.miniscape;

import java.awt.*;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.*;

public class CombatDebugOverlay extends Overlay
{
	private final Client client;
	private Set<Long> scannedChunks = null;

	@Inject
	CombatDebugOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(OverlayPriority.HIGH);
	}

	public void setScannedChunks(Set<Long> chunks)
	{
		this.scannedChunks = chunks;
	}

	private static long chunkKey(int chunkX, int chunkY)
	{
		return ((long) chunkX << 32) | (chunkY & 0xFFFFFFFFL);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		Player player = client.getLocalPlayer();
		if (player == null) return null;

		// Draw scanned area overlay — only chunk corners to keep it fast
		if (scannedChunks != null && !scannedChunks.isEmpty())
		{
			renderScannedChunks(g);
		}

		// Original combat debug overlay
		Actor target = player.getInteracting();
		if (target instanceof NPC)
		{
			renderCombatInfo(g, player, (NPC) target);
		}

		return null;
	}

	private void renderScannedChunks(Graphics2D g)
	{
		try
		{
			WorldView wv = client.getTopLevelWorldView();
			Scene scene = wv.getScene();
			Tile[][][] tiles = scene.getTiles();
			int plane = wv.getPlane();

			Color scannedColor = new Color(0, 255, 0, 80);
			Color unscannedColor = new Color(255, 0, 0, 50);
			g.setStroke(new BasicStroke(2));

			// Only check every 8th tile (chunk corners) to keep it fast
			for (int x = 0; x < Constants.SCENE_SIZE; x += 8)
			{
				for (int y = 0; y < Constants.SCENE_SIZE; y += 8)
				{
					if (x >= tiles[plane].length || y >= tiles[plane][x].length) continue;
					Tile tile = tiles[plane][x][y];
					if (tile == null) continue;

					WorldPoint wp = tile.getWorldLocation();
					int chunkX = wp.getX() / 8;
					int chunkY = wp.getY() / 8;
					boolean isScanned = scannedChunks.contains(chunkKey(chunkX, chunkY));

					// Draw a marker at each chunk corner
					LocalPoint lp = LocalPoint.fromWorld(wv, wp);
					if (lp == null) continue;

					// Draw a 2x2 tile area at chunk corner for visibility
					for (int dx = 0; dx < 2 && (x + dx) < Constants.SCENE_SIZE; dx++)
					{
						for (int dy = 0; dy < 2 && (y + dy) < Constants.SCENE_SIZE; dy++)
						{
							Tile t2 = tiles[plane][x + dx][y + dy];
							if (t2 == null) continue;
							LocalPoint lp2 = LocalPoint.fromWorld(wv, t2.getWorldLocation());
							if (lp2 == null) continue;
							Polygon poly = Perspective.getCanvasTilePoly(client, lp2);
							if (poly == null) continue;

							if (isScanned)
							{
								g.setColor(scannedColor);
								g.fillPolygon(poly);
								g.setColor(new Color(0, 255, 0, 150));
								g.drawPolygon(poly);
							}
							else
							{
								g.setColor(unscannedColor);
								g.fillPolygon(poly);
								g.setColor(new Color(255, 0, 0, 120));
								g.drawPolygon(poly);
							}
						}
					}
				}
			}

			// Draw scan stats text in top left
			g.setFont(new Font("Arial", Font.BOLD, 14));
			String stats = "Scanned: " + scannedChunks.size() + " chunks";
			g.setColor(Color.BLACK);
			g.drawString(stats, 11, 31);
			g.setColor(Color.GREEN);
			g.drawString(stats, 10, 30);
		}
		catch (Exception e)
		{
			// Scene might not be ready
		}
	}

	private void renderCombatInfo(Graphics2D g, Player player, NPC npc)
	{
		WorldPoint npcWorld = npc.getWorldLocation();
		WorldPoint playerWorld = player.getWorldLocation();

		// Draw NPC true tile
		LocalPoint npcLocal = LocalPoint.fromWorld(client.getTopLevelWorldView(), npcWorld);
		if (npcLocal != null)
		{
			Polygon poly = Perspective.getCanvasTilePoly(client, npcLocal);
			if (poly != null)
			{
				g.setColor(new Color(0, 255, 255, 100));
				g.fillPolygon(poly);
				g.setColor(new Color(0, 255, 255, 200));
				g.setStroke(new BasicStroke(1));
				g.drawPolygon(poly);
			}
		}

		// Draw info text above NPC
		String info = String.format("N:%d,%d dist:%d",
			npcWorld.getX(), npcWorld.getY(),
			playerWorld.distanceTo(npcWorld));

		LocalPoint npcLp = npc.getLocalLocation();
		if (npcLp != null)
		{
			net.runelite.api.Point textPoint = Perspective.getCanvasTextLocation(client, g, npcLp, info, npc.getLogicalHeight() + 40);
			if (textPoint != null)
			{
				g.setFont(new Font("Arial", Font.BOLD, 12));
				g.setColor(Color.BLACK);
				g.drawString(info, textPoint.getX() + 1, textPoint.getY() + 1);
				g.setColor(Color.CYAN);
				g.drawString(info, textPoint.getX(), textPoint.getY());
			}
		}
	}
}
