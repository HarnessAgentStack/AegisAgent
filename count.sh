#!/bin/bash
# usage: count.sh "ClassName"  -> counts distinct non-self files referencing name
awk -v cls="$1" '
/^===FILE:/ { cur=substr($0,9); if (index(cur, "/" cls ".java")>0 || cur ~ ("(^|/)" cls "\\.java$")) self=cur; next }
$0 ~ ("(^|[^A-Za-z0-9_])" cls "([^A-Za-z0-9_]|$)") { seen[cur]=1 }
END { n=0; for (f in seen) if (f!=self) n++; print n }
' /d/code/share/ai/aegis/allindex.txt
