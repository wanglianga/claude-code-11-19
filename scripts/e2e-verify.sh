#!/usr/bin/env bash
# 端到端验证：卡号换人 / 区间重叠 / 区间缺失 / 缺期初 / 断档补齐 / 撤销先到 / 重复与冲突 / 切换中断与完整切换
set -u
BASE=http://127.0.0.1:8080
PASS=0; FAIL=0
ok()   { echo "  [PASS] $1"; PASS=$((PASS+1)); }
bad()  { echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }
assert_eq() { # expected actual message
  if [ "$1" == "$2" ]; then ok "$3 (= $2)"; else bad "$3 (expected=$1 actual=$2)"; fi
}
code_of() {
  for i in 1 2 3; do
    local c; c=$(curl -m 15 -s -o /tmp/resp.json -w '%{http_code}' "$@")
    [ "$c" != "000" ] && { echo "$c"; return; }
    sleep 1
  done
  echo 000
}
post() {
  for i in 1 2 3; do
    local out; out=$(curl -m 20 -s -X POST -H 'Content-Type: application/json' "${@}")
    echo "$out" | grep -q '"status":500' && [ "$i" -lt 3 ] && { sleep 1; continue; }
    echo "$out"; return
  done
}
getj() {
  for i in 1 2 3; do
    local out; out=$(curl -m 15 -s "$1")
    [ -n "$out" ] && { echo "$out"; return; }
    sleep 1
  done
}

echo "== 0. 全量重置 =="
post "$BASE/api/legacy/system/reset" | jq -e '.reset==true' >/dev/null && ok "重置成功" || bad "重置失败"

echo "== 1. 快照：清单先行、未齐不得完成、分段校验 =="
M=$(post "$BASE/api/snapshot/manifest")
echo "$M" | jq -e '.cutoff_seq==100 and .total_sections==4 and .status=="LISTED"' >/dev/null \
  && ok "清单含截止序号100与4个分段" || bad "清单内容异常: $(echo "$M" | jq -c '{cutoff_seq,total_sections,status}')"
C=$(code_of -X POST "$BASE/api/snapshot/complete"); assert_eq 412 "$C" "清单未齐时完成基线被拒"
for sec in MEMBER CARD PURCHASE OPENING; do
  # 小分页接收，验证游标续接
  while :; do
    R=$(post "$BASE/api/snapshot/sections/$sec/receive?limit=3")
    echo "$R" | jq -e '.has_more==false' >/dev/null && break
  done
  V=$(post "$BASE/api/snapshot/sections/$sec/verify")
  echo "$V" | jq -e '.status=="VERIFIED"' >/dev/null && ok "分段 $sec 哈希校验通过" || bad "分段 $sec 校验失败: $V"
done
S=$(post "$BASE/api/snapshot/complete")
echo "$S" | jq -e '.status=="COMPLETED"' >/dev/null && ok "四段齐备后基线完成" || bad "基线完成失败: $S"

echo "== 2. 基线投影：卡号换人/重叠/缺失 =="
P=$(post "$BASE/api/snapshot/project-baseline")
echo "$P" | jq -e '.members==7 and .cards==7 and .purchases==9' >/dev/null \
  && ok "投影 7会员/7卡区间/9交易" || bad "投影数量异常: $P"
curl -s "$BASE/api/reports/members/M001" | jq -e '[.adopted_purchases[].trans_no]|index("T1002")' >/dev/null \
  && ok "换卡前 T1002 归属 M001" || bad "换卡前 T1002 归属异常"
curl -s "$BASE/api/reports/members/M002" | jq -e '[.adopted_purchases[].trans_no]|index("T1003")' >/dev/null \
  && ok "回收重发后 T1003 归属 M002" || bad "回收重发后 T1003 归属异常"
BLK=$(curl -s "$BASE/api/ownership/blocked")
echo "$BLK" | jq -e 'map(.trans_no) | index("T1004") and index("T1005")' >/dev/null \
  && ok "重叠 T1004 与缺失 T1005 均进入待判定" || bad "待判定清单异常: $(echo "$BLK" | jq -c 'map(.trans_no)')"
echo "$BLK" | jq -e '.[]|select(.trans_no=="T1004")|.candidates|length==2' >/dev/null \
  && ok "T1004 列出两名候选持有人及持卡起止" || bad "T1004 候选异常"
# 开放前新端查询被同侧守卫拒绝
assert_eq 412 "$(code_of "$BASE/api/target/members")" "未开放时新端业务查询被拒"

echo "== 3. 变更流：断档暂停 -> 补齐续接 =="
R=$(post "$BASE/api/changes/pull?limit=50")
echo "$R" | jq -e '.paused==true and .contiguous_seq==103 and .gap_at==104' >/dev/null \
  && ok "104 断档：应用到103后暂停" || bad "断档处理异常: $R"
C=$(code_of -X POST "$BASE/api/changes/pull?limit=10"); assert_eq 412 "$C" "暂停期间拒绝继续拉取"
post "$BASE/api/legacy/gap/fill-104" | jq -e '.filled==true' >/dev/null && ok "旧端补齐104" || bad "补齐失败"
post "$BASE/api/changes/resume" | jq -e '.paused==false' >/dev/null && ok "补齐后续接解除暂停" || bad "续接失败"
R=$(post "$BASE/api/changes/pull?limit=50")
echo "$R" | jq -e '.contiguous_seq==110 and .paused==false' >/dev/null \
  && ok "追平到最终序号110" || bad "追平异常: $R"

echo "== 4. 撤销先到、重复事件、同序号冲突 =="
curl -s "$BASE/api/ownership/transactions/T2005/candidates" | jq -e '.transaction[0].valid_flag==false' >/dev/null \
  && ok "撤销106先到、原交易107到达后联动生效，T2005 已撤销" || bad "撤销联动异常"
# 直接从旧端原件结构构造同哈希重发报文
E101=$(PGPASSWORD=ccdev psql -h 127.0.0.1 -U ccdev -d ccdev -tA -c "SELECT row_to_json(x) FROM (SELECT seq, source_id, entity_type, entity_key, op, payload, content_hash, business_time FROM legacy_change_event WHERE seq=101) x")
DUP=$(post "$BASE/api/changes/ingest" -d "$E101")
echo "$DUP" | jq -e '.apply_status=="DUPLICATE"' >/dev/null && ok "同序号同哈希识别为重复，余额不变" || bad "重复识别异常: $DUP"
CONFLICT=$(curl -s "$BASE/api/legacy/conflict/replay/105")
R=$(post "$BASE/api/changes/ingest" -d "$CONFLICT")
echo "$R" | jq -e '.apply_status=="CONFLICT" and .paused==true' >/dev/null && ok "同序号不同内容报告冲突并暂停" || bad "冲突识别异常: $R"
R=$(post "$BASE/api/changes/conflicts/105/resolve?action=KEEP_ORIGINAL")
echo "$R" | jq -e '.paused==false' >/dev/null && ok "冲突处置 KEEP_ORIGINAL 后续接" || bad "冲突处置异常: $R"

echo "== 5. 人工判定：证据约束、候选约束、原判定可查、局部重算 =="
C=$(code_of -X POST "$BASE/api/ownership/decisions" -H 'Content-Type: application/json' \
  -d '{"trans_no":"T1004","owner_code":"M999","evidence":"纸面凭证12345","decided_by":"op1"}')
assert_eq 412 "$C" "非候选会员判定被拒"
C=$(code_of -X POST "$BASE/api/ownership/decisions" -H 'Content-Type: application/json' \
  -d '{"trans_no":"T1004","owner_code":"M004","evidence":"abc","decided_by":"op1"}')
assert_eq 400 "$C" "证据不足5字被拒（禁止按余额接近配对）"
post "$BASE/api/ownership/decisions" -H 'Content-Type: application/json' \
  -d '{"trans_no":"T1004","owner_code":"M004","evidence":"门店纸质签购单核对签名","decided_by":"op1"}' | jq -e '.decided_owner=="M004"' >/dev/null \
  && ok "T1004 凭证据判定 M004" || bad "T1004 判定失败"
post "$BASE/api/ownership/decisions" -H 'Content-Type: application/json' \
  -d '{"trans_no":"T1004","owner_code":"M003","evidence":"补充监控记录确认是王芳本人","decided_by":"op2"}' | jq -e '.decided_owner=="M003"' >/dev/null \
  && ok "T1004 修正为 M003" || bad "T1004 修正失败"
curl -s "$BASE/api/ownership/decisions/T1004" | jq -e 'length==2 and (map(select(.superseded==true))|length==1)' >/dev/null \
  && ok "原判定仍可查且标记 superseded" || bad "判定历史异常"
C=$(code_of -X POST "$BASE/api/ownership/decisions" -H 'Content-Type: application/json' \
  -d '{"trans_no":"T1005","owner_code":"M999","evidence":"门店纸质签购单核对签名","decided_by":"op1"}')
assert_eq 412 "$C" "区间缺失也不能指定给不存在的会员"
post "$BASE/api/ownership/decisions" -H 'Content-Type: application/json' \
  -d '{"trans_no":"T1005","owner_code":"M005","evidence":"停业门店留存的纸质签购单","decided_by":"op1"}' | jq -e '.decided_owner=="M005"' >/dev/null \
  && ok "区间缺失 T1005 凭证据指定 M005" || bad "T1005 判定失败"

echo "== 6. 缺期初：阻断、禁止填平、凭证据补录 =="
RC=$(post "$BASE/api/reconcile/check")
echo "$RC" | jq -e '.overall_passed==false and ([.diffs[]|select(.diff_type=="MISSING_OPENING" and .ref_key=="M007")]|length==1)' >/dev/null \
  && ok "核对报 M007 缺期初且整体不通过" || bad "缺期初核对异常"
curl -s "$BASE/api/reports/members/M007" | jq -e '.balance[0].blocked==true and (.balance[0].block_reason|test("MISSING_OPENING"))' >/dev/null \
  && ok "M007 余额阻断并给出原因，未造调整" || bad "M007 阻断异常"
C=$(code_of -X POST "$BASE/api/ownership/openings/evidence" -H 'Content-Type: application/json' \
  -d '{"member_code":"M007","opening_points":60,"evidence":"abc","recorded_by":"op1"}')
assert_eq 400 "$C" "无充分证据的期初补录被拒"
post "$BASE/api/ownership/openings/evidence" -H 'Content-Type: application/json' \
  -d '{"member_code":"M007","opening_points":60,"evidence":"停业门店交接清单登记期初60分","recorded_by":"op1"}' | jq -e '.opening_points==60' >/dev/null \
  && ok "凭证据补录 M007 真实期初60" || bad "期初补录失败"

echo "== 7. 余额形成过程与会员报告 =="
curl -s "$BASE/api/reports/members/M001" | jq -e '
  .point_ledger[0].entry_type=="OPENING" and .point_ledger[0].points_delta==100
  and ([.point_ledger[]|select(.entry_type=="PURCHASE")]|length==3)
  and .balance[0].balance_points==135 and .balance[0].blocked==false' >/dev/null \
  && ok "M001 期初100+3笔有效变动=135（7月后C9001交易归M002），台账逐笔可查" || bad "M001 台账异常: $(curl -s "$BASE/api/reports/members/M001" | jq -c '.point_ledger,.balance')"
curl -s "$BASE/api/target/members" >/dev/null 2>&1
PGPASSWORD=ccdev psql -h 127.0.0.1 -U ccdev -d ccdev -tA -c "SELECT count(*) FROM target_member WHERE phone='13800000004'" | grep -q 2 \
  && ok "同手机号家庭成员 M004/M005 保留为两个独立会员，未自动合并" || bad "家庭成员合并异常"

echo "== 8. 核对通过与下钻 =="
RC=$(post "$BASE/api/reconcile/check")
echo "$RC" | jq -e '.overall_passed==true and .diff_count==0
  and .summary.source_member_count==8 and .summary.target_member_count==8
  and .summary.applied_seq==110 and .summary.seq_backlog==0' >/dev/null \
  && ok "核对通过：8会员、序号追平110、零差异" || bad "核对未通过: $(echo "$RC" | jq -c '{passed:.overall_passed,diff_count,summary:.summary.seq_backlog,diffs:[.diffs[].diff_type]}')"
curl -s "$BASE/api/reconcile/stores/S01" | jq -e '(.members|length>=5) and (.balances|length>0)' >/dev/null \
  && ok "门店 S01 下钻数据完整" || bad "门店下钻异常"

echo "== 9. 切换：共同条件、双申请互斥、中断恢复、停写拒写 =="
curl -s "$BASE/api/switch/conditions" | jq -e '.all_met==false and (.unmet|index("旧端未停写"))!=null and .estimated_backlog_events==0' >/dev/null \
  && ok "条件面板展示未满足项且积压为0" || bad "条件面板异常"
REQ=$(post "$BASE/api/switch/requests" -H 'Content-Type: application/json' -d '{"requested_by":"alice","requested_cutoff":110}')
RID=$(echo "$REQ" | jq -r '.id')
echo "$REQ" | jq -e '.status=="FREEZE_REQUESTED"' >/dev/null && ok "停写申请已保存 id=$RID" || bad "申请异常: $REQ"
C=$(code_of -X POST "$BASE/api/switch/requests" -H 'Content-Type: application/json' -d '{"requested_by":"bob","requested_cutoff":108}')
assert_eq 409 "$C" "两人不同截点的第二份申请被拒"
# 中断恢复演示：未停写时直接目标应用应失败并给恢复步骤
C=$(code_of -X POST "$BASE/api/switch/requests/$RID/apply-target"); assert_eq 412 "$C" "未满足条件时目标应用被阻断"
curl -s "$BASE/api/switch/requests/$RID" | jq -e '.status=="FAILED" and (.recovery_step|length>10)' >/dev/null \
  && ok "失败态记录明确恢复步骤，超时查询可见实际阶段" || bad "失败恢复信息异常"
post "$BASE/api/switch/requests/$RID/confirm-final-seq?actor=alice" | jq -e '.status=="FINAL_SEQ_CONFIRMED" and .final_seq==110' >/dev/null \
  && ok "旧端确认最终序号110并停写" || bad "最终序号确认异常"
C=$(code_of -X POST "$BASE/api/legacy/business/purchase" -H 'Content-Type: application/json' \
  -d '{"trans_no":"TX","card_no":"C9001","store_code":"S01","trans_time":"2025-10-01T10:00:00","amount":"10.00","points_delta":1}')
assert_eq 409 "$C" "停写后旧端拒绝新营业写入"
post "$BASE/api/switch/requests/$RID/apply-target?actor=alice" | jq -e '.status=="TARGET_APPLIED"' >/dev/null \
  && ok "目标应用完成（内部重跑核对通过）" || bad "目标应用异常"
post "$BASE/api/switch/requests/$RID/open?actor=alice" | jq -e '.status=="OPENED"' >/dev/null \
  && ok "四项共同条件满足，新端开放" || bad "开放异常"
C=$(code_of -X POST "$BASE/api/switch/requests/$RID/cancel" -H 'Content-Type: application/json' -d '{"actor":"alice","reason":"test"}')
assert_eq 409 "$C" "开放后不得自动退回"
curl -s "$BASE/api/target/side" | jq -e '.read_side=="TARGET" and .target_opened==true' >/dev/null \
  && ok "开放后查询固定来自新端同一侧完整范围" || bad "读取侧异常"
curl -s "$BASE/api/target/balances" | jq -e "map(select(.member_code==\"M007\"))[0].balance_points==66" >/dev/null \
  && ok "新端余额查询可用，M007=期初60+T1007的6=66" || bad "新端余额异常"

echo "== 10. 取消切换路径（独立一轮） =="
post "$BASE/api/legacy/system/reset" >/dev/null
post "$BASE/api/snapshot/manifest" >/dev/null
for sec in MEMBER CARD PURCHASE OPENING; do
  while :; do R=$(post "$BASE/api/snapshot/sections/$sec/receive?limit=3"); echo "$R" | jq -e '.has_more==false' >/dev/null && break; done
  post "$BASE/api/snapshot/sections/$sec/verify" >/dev/null
done
post "$BASE/api/snapshot/complete" >/dev/null; post "$BASE/api/snapshot/project-baseline" >/dev/null
for i in 1 2 3; do post "$BASE/api/changes/pull?limit=50" >/dev/null; post "$BASE/api/legacy/gap/fill-104" >/dev/null 2>&1; post "$BASE/api/changes/resume" >/dev/null 2>&1; done
RID2=$(post "$BASE/api/switch/requests" -H 'Content-Type: application/json' -d '{"requested_by":"carol","requested_cutoff":110}' | jq -r '.id')
post "$BASE/api/switch/requests/$RID2/confirm-final-seq?actor=carol" >/dev/null
post "$BASE/api/switch/requests/$RID2/cancel" -H 'Content-Type: application/json' -d '{"actor":"carol","reason":"运营暂停切换"}' | jq -e '.status=="CANCELLED"' >/dev/null \
  && ok "未开放时批准取消" || bad "取消异常"
curl -s "$BASE/api/legacy/state" | jq -e '.frozen==false' >/dev/null && ok "取消后旧端重新开放写入" || bad "旧端未恢复"
post "$BASE/api/legacy/business/purchase" -H 'Content-Type: application/json' \
  -d '{"trans_no":"T3001","card_no":"C9001","store_code":"S01","trans_time":"2025-10-02T10:00:00","amount":"20.00","points_delta":2}' | jq -e '.accepted==true' >/dev/null \
  && ok "旧端恢复后可继续营业记账" || bad "恢复后营业写入异常"

echo
echo "RESULT: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
