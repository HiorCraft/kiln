package dev.kiln.client.entity.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeoModelTest {
    private val geo = """
        {"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.test","texture_width":64,"texture_height":32},
         "bones":[
          {"name":"root","pivot":[0,0,0]},
          {"name":"arm","parent":"root","pivot":[4,12,0],"rotation":[0,0,0],
           "cubes":[{"origin":[4,0,-1],"size":[2,12,2],"uv":[0,0]}]},
          {"name":"head","parent":"root","pivot":[0,12,0],
           "cubes":[{"origin":[-2,12,-2],"size":[4,4,4],"uv":{"north":{"uv":[8,8],"uv_size":[4,4]}}}]}
         ]}]}
    """.trimIndent()

    private val animations = """
        {"format_version":"1.8.0","animations":{
          "animation.test.walk":{"loop":true,"animation_length":1.0,"bones":{
            "arm":{"rotation":{"0.0":[0,0,0],"0.5":[90,0,0],"1.0":[0,0,0]}},
            "head":{"position":[0,1,0]}}},
          "animation.test.smash":{"animation_length":0.5,"bones":{
            "arm":{"rotation":{"0.0":{"post":[0,0,0],"lerp_mode":"catmullrom"},"0.25":{"post":[-80,0,0],"lerp_mode":"catmullrom"},"0.5":[0,0,0]}}}},
          "animation.test.blink":{"loop":"hold_on_last_frame","bones":{"head":{"scale":{"0.0":[1,1,1],"0.2":{"post":[1,0.5,1],"lerp_mode":"step"}}}}}
        }}
    """.trimIndent()

    @Test
    fun bonesCubesAndUvAreParsed() {
        val model = GeoModel.parse(geo)
        assertEquals(64, model.textureWidth)
        assertEquals(listOf("root", "arm", "head"), model.bones.map { it.name })
        val arm = model.bones[1]
        assertEquals("root", arm.parent)
        assertEquals(1, arm.cubes.size)
        assertTrue(arm.cubes[0].uv is GeoUv.Box)
        assertTrue(model.bones[2].cubes[0].uv is GeoUv.Faces)
    }

    @Test
    fun unknownParentAndHugeModelsAreRejected() {
        assertThrows(GeoException::class.java) { GeoModel.parse(geo.replace("\"parent\":\"root\",\"pivot\":[4", "\"parent\":\"ghost\",\"pivot\":[4")) }
        val many = (0..256).joinToString(",") { """{"name":"b$it"}""" }
        assertThrows(GeoException::class.java) {
            GeoModel.parse("""{"minecraft:geometry":[{"description":{"texture_width":16,"texture_height":16},"bones":[$many]}]}""")
        }
    }

    @Test
    fun animationNamesAreShortened() {
        val set = GeoAnimations.parse(animations)
        assertEquals(setOf("walk", "smash", "blink"), set.animations.keys)
        assertEquals(GeoLoop.LOOP, set.animations.getValue("walk").loop)
        assertEquals(GeoLoop.HOLD, set.animations.getValue("blink").loop)
        assertEquals(0.5, set.animations.getValue("smash").length, 1e-9)
    }

    @Test
    fun linearKeyframesInterpolate() {
        val walk = GeoAnimations.parse(animations).animations.getValue("walk")
        val rot = walk.bones.getValue("arm").rotation!!
        assertEquals(45.0, rot.sample(0.25).x, 1e-6)
        assertEquals(90.0, rot.sample(0.5).x, 1e-6)
        // a constant channel
        assertEquals(1.0, walk.bones.getValue("head").position!!.sample(0.7).y, 1e-6)
    }

    @Test
    fun catmullromHitsKeyframesAndStepHolds() {
        val set = GeoAnimations.parse(animations)
        val smash = set.animations.getValue("smash").bones.getValue("arm").rotation!!
        assertEquals(-80.0, smash.sample(0.25).x, 1e-6)
        assertTrue(smash.sample(0.125).x < 0)
        val blink = set.animations.getValue("blink").bones.getValue("head").scale!!
        assertEquals(1.0, blink.sample(0.19).y, 1e-6)
        assertEquals(0.5, blink.sample(0.2).y, 1e-6)
        assertEquals(0.5, blink.sample(5.0).y, 1e-6)
    }

    @Test
    fun meshFollowsBoneRotation() {
        val model = GeoModel.parse(geo)
        val rest = GeoMesh.vertices(model, emptyMap())
        // arm: 6 faces (box UV), head: only its north face (per-face UV) -> 7 quads
        assertEquals(28, rest.size)
        // the arm hangs down from y=12 to y=0 at rest; rotated 90° around x (bedrock) its lowest point is at the pivot height
        val armRest = rest.take(24)
        assertEquals(0.0, armRest.minOf { it.y }, 1e-6)
        val swung = GeoMesh.vertices(model, mapOf("arm" to BonePose(rotation = Vec3d(90.0, 0.0, 0.0)))).take(24)
        assertTrue(swung.minOf { it.y } > 10.9, "lowest arm vertex ${swung.minOf { it.y }}")
        // the bedrock x axis is mirrored into Minecraft's: the arm (bedrock x 4..6) ends up at x -6..-4
        assertEquals(-6.0, armRest.minOf { it.x }, 1e-6)
        assertNotNull(armRest.first().u)
    }
}
