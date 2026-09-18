# -*- coding: utf-8 -*-
import io

p = "src/main/java/com/jessetech/logiscope/LogiScopeMod.java"
s = io.open(p, encoding="utf-8").read()

old = "import com.jessetech.logiscope.bind.ContainerAnchor;"
assert old in s
s = s.replace(old, old + "\nimport com.jessetech.logiscope.bind.DataCommand;", 1)

old2 = "import net.neoforged.neoforge.client.event.ClientTickEvent;"
assert old2 in s
s = s.replace(old2, "import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;\n" + old2, 1)

old3 = """        // 没有界面打开时，Screen 按键事件不触发；补一条走标准 KeyMapping 的路径，"""
new3 = """        // 「Probe data」：抓 `data get block` 的系统回执（只认我们刚发出去的那一条），
        // 并顺手吞掉它 —— 界面已经显示了，不必再刷聊天框
        NeoForge.EVENT_BUS.<ClientChatReceivedEvent.System>addListener(DataCommand::onChatReceived);

        // 命令回执的超时计时（1 秒没回就当失败，不然状态永远卡在"等待"）
        NeoForge.EVENT_BUS.<ClientTickEvent.Post>addListener(e -> DataCommand.tick());

        // 没有界面打开时，Screen 按键事件不触发；补一条走标准 KeyMapping 的路径，"""
assert old3 in s
s = s.replace(old3, new3, 1)

io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("LogiScopeMod ok")
