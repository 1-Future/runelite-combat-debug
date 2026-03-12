package com.miniscape;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import javax.swing.*;
import net.runelite.client.ui.PluginPanel;

public class CombatDebugPanel extends PluginPanel
{
	private final JTextArea logArea;
	private final StringBuilder logBuffer = new StringBuilder();
	private Runnable scanCallback;

	public void setScanCallback(Runnable cb)
	{
		this.scanCallback = cb;
	}

	public CombatDebugPanel()
	{
		setLayout(new BorderLayout());

		logArea = new JTextArea();
		logArea.setEditable(false);
		logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
		logArea.setBackground(new Color(30, 30, 30));
		logArea.setForeground(new Color(200, 200, 200));
		logArea.setLineWrap(true);
		logArea.setWrapStyleWord(true);

		JScrollPane scrollPane = new JScrollPane(logArea);
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
		add(scrollPane, BorderLayout.CENTER);

		JPanel buttonPanel = new JPanel(new GridLayout(1, 3, 4, 0));

		JButton scanBtn = new JButton("Scan Nearby");
		scanBtn.setBackground(new Color(34, 197, 94));
		scanBtn.setForeground(Color.WHITE);
		scanBtn.addActionListener(e -> {
			if (scanCallback != null) scanCallback.run();
		});

		JButton copyBtn = new JButton("Copy Log");
		copyBtn.addActionListener(e -> {
			StringSelection sel = new StringSelection(logBuffer.toString());
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
			copyBtn.setText("Copied!");
			Timer timer = new Timer(1500, ev -> copyBtn.setText("Copy Log"));
			timer.setRepeats(false);
			timer.start();
		});

		JButton clearBtn = new JButton("Clear");
		clearBtn.addActionListener(e -> {
			logBuffer.setLength(0);
			logArea.setText("");
		});

		buttonPanel.add(scanBtn);
		buttonPanel.add(copyBtn);
		buttonPanel.add(clearBtn);
		add(buttonPanel, BorderLayout.SOUTH);
	}

	public void addLine(String line)
	{
		logBuffer.append(line).append("\n");
		SwingUtilities.invokeLater(() -> {
			logArea.append(line + "\n");
			logArea.setCaretPosition(logArea.getDocument().getLength());
		});
	}
}
