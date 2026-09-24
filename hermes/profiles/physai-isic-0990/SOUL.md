# physai-isic-0990 — 鉱業の支援サービス（ISIC 0990）の physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-isic-0990`、ISIC Rev.5 0990 その他の鉱業支援サービス）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README の Robotics premise: 調整ロボットが、Mining Services Governor の下でサービス受注・クルー派遣・資材物流を行う（発破・採掘・鉱山保安の判断はオペレータ専権で恒久的に遮断）。
その物理的な仕事（坑内での資材運搬とコア箱の積み下ろし）を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:consumables-delivery-decline` | transport | 鉱山物流ロボット（1.5 t + 資材 0.5 t）が斜坑のランプを 400 m 登って請負業者の切羽へ資材を届ける（勾配を掃引） | 1 区間の所要時間 | 240 s（estimate） |
| `:core-box-lift` | manipulator | アームが満載のコア箱をリグのトレイから運搬パレットへ持ち上げる（積荷を掃引） | 肩関節ピークトルク | 300 N·m（estimate） |

測定の入口: `kbb -M:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:physai-test`（`test-physai/mining_services/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する。repo 自身の `test/` も同じ runner で走る: 12 tests / 39 assertions、0 fail）。

## 測って分かったこと・限界（成長の第一候補）

1. **斜坑運搬**: 勾配 0〜6° で所要時間は 164.39 s（速度上限 2.5 m/s と加速度上限 0.4 m/s² が効く）。8° で 164.9 s（駆動力が効き始める）、
   **10° で 330.83 s に跳ね上がり（限界外）、12° では駆動力 4000 N が勾配＋転がり抵抗に負けて停止**。限界 240 s に達する勾配は **9.95°**。
   エネルギーは 0° 0.24 MJ → 10° 1.59 MJ、転倒余裕は 0.91 → 0.75。一般的な斜坑勾配（約 1:7 ≒ 8°）の直上に崖がある。
2. **アーム**: 肩トルクは 5 kg で 133.2 N·m、25 kg で 286.9 N·m。限界 300 N·m に達する積荷は **26.7 kg**。
3. **estimate のままの値（成長候補）**:
   - 1 区間 240 s（シフト計画のサービス枠で置き換える）
   - 駆動力 4000 N・転がり抵抗係数 0.03（坑内路面。実機の諸元で置き換える）
   - 肩トルク 300 N·m（アームの仕様書で置き換える）

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種・職種のロボットがする別の物理的な仕事を 1 case 足す（`:kind` は :transport / :manipulator / :material /
   :thermal / :tank-drain / :pipe-flow）。README の premise と docs から根拠を取る（例: コア箱の坑外への運搬、坑内排水ポンプの配管損失）。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-isic-0990 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:physai-test → kbb -M:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-isic-0990 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
