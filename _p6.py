# -*- coding: utf-8 -*-
import io

p = "dev-check/TestChart.java"
s = io.open(p, encoding="utf-8").read()
bs = chr(92)          # backslash
broken = 'String raw = "barrel has the following block data: {a:1,bb:22,c://"xyz//"}";'
correct = 'String raw = "barrel has the following block data: {a:1,bb:22,c:' + bs + '"xyz' + bs + '"}";'
assert broken in s, "broken line not found"
s = s.replace(broken, correct, 1)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("fixed")
