package com.openscape;

import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import java.awt.*;
import javax.inject.Inject;

public class CombatDebugOverlay extends Overlay
{
	@Inject
	public CombatDebugOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		return null;
	}
}
