package zeg.content;

import arc.graphics.Color;
import arc.math.Mathf;
import arc.math.geom.Vec3;
import arc.util.Log;
import arc.util.noise.Simplex;
import mindustry.content.Blocks;
import mindustry.content.Weathers;
import mindustry.game.Rules;
import mindustry.maps.generators.PlanetGenerator;
import mindustry.type.Sector;
import mindustry.type.Weather.WeatherEntry;

/**
 * 斯维特顿（Frost 母星）冰原地形生成器。
 *
 * 调用链（已对照 Mindustry v160 源码核过）：
 *   PlanetGenerator.generate(Tiles, Sector, WorldParams)
 *     → 逐格 genTile() 建 Tile（TileGen.reset() 默认 floor = Blocks.stone）
 *     → BasicGenerator.generate(Tiles, WorldParams) → generate()   ← 本类逻辑在这里
 *
 * ────────────────────────────────────────────────────────
 * 【本次修订修了什么】
 *
 * ① 噪声阈值全错（重要）
 *    Arc 的 Simplex.noise2d / noise3d 里是 (raw+1)/2 再归一化，
 *    返回值是 **[0, 1]**，不是 [-1, 1]。
 *    上一版按 [-1,1] 写的 snowThreshold=0.38 / iceThreshold=-0.05 /
 *    stoneThreshold=-0.42，结果几乎全落进同一个分支，salt 分支永远不触发。
 *    现在全部改到 [0,1] 区间。
 *
 * ② 不再用继承来的 noise()
 *    PlanetGenerator 覆写的 noise() 内部是 sector.rect.project(x, y)
 *    再套 Simplex.noise3d(..., 1f/scl, ...)，x/y 是像素坐标走球面投影，
 *    行为不可控（原版 SerpuloPlanetGenerator 都得自己再覆写一次并 .scl(5f)）。
 *    这里改用 localNoise()：直接对网格坐标做 2D 噪声，结果可预测。
 *
 * ③ 加了 Log.info 诊断
 *    日志里出现 "[ZEG] SvittonPlanetGenerator.generate() 已执行" 才说明
 *    这份代码真的跑了。没出现 = 游戏读的是存档里缓存的旧地图。
 */
public class SvittonPlanetGenerator extends PlanetGenerator {

    // ────────────── 可调参数（阈值都在 [0,1]） ──────────────

    /** 地形起伏尺度。越大 = 地块越大块、越少碎斑 */
    public float terrainScl = 34f;

    /** 地板分带：噪声均值约 0.5，四段各占一部分 */
    public float snowThreshold  = 0.60f;
    public float iceThreshold   = 0.48f;
    public float stoneThreshold = 0.36f;

    /** 静态墙生成概率。墙是 solid，别调太高，否则没地方摆建筑 */
    public float wallChance = 0.10f;

    /** 出生点清空半径（格）。只清墙，不清矿，保证开局能起钻井 */
    public float spawnClearRadius = 14f;

    /** 直接用网格坐标算 2D 噪声，返回 [0,1] */
    float localNoise(float x, float y, float scl, float offset){
        return Simplex.noise2d(seed + (int)offset, 4, 0.6, 1f / scl, x + offset, y + offset);
    }

    // ────────────── 星球球面外观（HexMesher） ──────────────

    /** 球面噪声，返回 [0,1] */
    float rawHeight(Vec3 position){
        return Simplex.noise3d(seed, 4, 0.6, 1f / 2.2f,
                position.x, position.y, position.z);
    }

    @Override
    public float getHeight(Vec3 position){
        return Mathf.clamp(rawHeight(position));
    }

    @Override
    public void getColor(Vec3 position, Color out){
        float h = rawHeight(position);
        if(h > snowThreshold) out.set(Blocks.snow.mapColor);
        else if(h > iceThreshold) out.set(Blocks.ice.mapColor);
        else if(h > stoneThreshold) out.set(Blocks.stone.mapColor);
        else out.set(Blocks.darksand.mapColor);
        out.a(1f);
    }

    // ────────────── 区块地形 ──────────────

    @Override
    protected void generate(){
        Log.info("[ZEG] SvittonPlanetGenerator.generate() 已执行, size=@x@", width, height);

        float cx = width / 2f, cy = height / 2f;

        // ① 地板分带：积雪高地 → 冰盖 → 冻石 → 深色冰岩
        pass((x, y) -> {
            float h = localNoise(x, y, terrainScl, 0f);

            if(h > snowThreshold) floor = Blocks.snow;
            else if(h > iceThreshold) floor = Blocks.ice;
            else if(h > stoneThreshold) floor = Blocks.stone;
            else floor = Blocks.darksand;

            // 低洼处撒盐斑，避免整片单色
            if(h < 0.30f && localNoise(x, y, 9f, 137f) > 0.62f){
                floor = Blocks.salt;
            }
        });

        // ② 矿脉 + 静态墙 + 出生点清理
        pass((x, y) -> {
            // 原冰：铺在冰 / 雪地板上
            if((floor == Blocks.ice || floor == Blocks.snow)
                    && localNoise(x, y, 16f, 311f) > 0.62f
                    && rand.chance(0.45f)){
                ore = ZegBlocks.riverIce;
            }
            // 零铁：铺在冻石地板上
            else if(floor == Blocks.stone
                    && localNoise(x, y, 20f, 577f) > 0.64f
                    && rand.chance(0.40f)){
                ore = ZegBlocks.oreZeroIron;
            }

            // 少量静态墙，且不盖住矿脉
            // heavyIceWall 带 attrRawIce，ZegBlocks.iceWallCrusher 正好能采
            if(ore == Blocks.air
                    && rand.chance(wallChance)
                    && localNoise(x, y, 22f, 911f) > 0.58f){
                if(floor == Blocks.snow) block = ZegBlocks.permafrostWall;
                else if(floor == Blocks.ice) block = ZegBlocks.heavyIceWall;
                else if(floor == Blocks.stone || floor == Blocks.darksand) block = ZegBlocks.frozenStoneWall;
            }

            // 出生点：只清墙，保留矿，开局能直接架钻井
            if(Mathf.dst(x, y, cx, cy) < spawnClearRadius){
                block = Blocks.air;
            }
        });
    }

    // ────────────── 天气 ──────────────

    /** 强制下雪，呼应 Frost（雪霜）阵营设定，不依赖基类的地板名猜测 */
    @Override
    public void addWeather(Sector sector, Rules rules){
        rules.weather.clear();
        rules.weather.add(new WeatherEntry(Weathers.snow));
    }
}
