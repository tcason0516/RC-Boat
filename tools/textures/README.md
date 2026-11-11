# Photo textures

The images in `app/src/main/assets/tex/` are photographed, public-domain (CC0 1.0) textures.

| File | Source asset | Library |
| --- | --- | --- |
| `grass_a.jpg` | Grass004 | ambientCG |
| `grass_b.jpg` | Grass001 | ambientCG |
| `rock.jpg` | Rock023 | ambientCG |
| `sand.jpg` | sand_01 | Poly Haven |
| `pebbles.jpg` | river_small_rocks | Poly Haven |
| `boulder.jpg` | mossy_rock | Poly Haven |
| `bark.jpg` | pine_bark | Poly Haven |
| `planks.jpg` | weathered_planks | Poly Haven |
| `sky.jpg` | kloofendal_48d_partly_cloudy_puresky (HDRI) | Poly Haven |

The surface textures were taken as 512 px KTX2 files from the CC0 mirror
<https://github.com/Papyszoo/CC0-Public-Domain-Textures> and decoded with `decode.cjs`
(Basis Universal transcoder from the `three` npm package) and `topng.py`.

The sky is the 2k `.hdr` panorama, processed by `sky.py`: it finds the sun, rotates the
panorama so the sun sits at the game's light azimuth, tone-maps the upper hemisphere to a
2048x512 JPG, and prints the matching `LIGHT` and `FOG` values used in `Scenery.java`.

Both libraries publish everything under CC0: <https://polyhaven.com/license>,
<https://docs.ambientcg.com/license/>.
