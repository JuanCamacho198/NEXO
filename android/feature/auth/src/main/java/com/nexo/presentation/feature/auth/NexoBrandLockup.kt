package com.nexo.presentation.feature.auth
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.unit.dp

/**
 * The NEXO horizontal brand lockup — tile, ribbon, knocked-out "N" and the
 * "EXO" part of the wordmark — as [ImageVector]s.
 *
 * GENERATED. Do not hand-edit the path data: run
 * `scripts/brand/Sync-AndroidBrandDrawables.ps1` and
 * `scripts/brand/Sync-AndroidBrandLockup.ps1` after a change to brand/.
 *
 * Two-colour art cannot go through [com.nexo.ui.icons.NexoIcons], whose icons
 * are single-colour and recoloured by `Icon(tint = ...)`. The fills are fixed
 * here, so the caller picks the variant that matches its background:
 *
 * - [Light] — navy wordmark ink, for light surfaces.
 * - [Dark]  — cream wordmark ink, for dark surfaces.
 *
 * The tile, the ribbon and the "N" keep their brand colours in BOTH variants.
 * Only the wordmark flips, which is what the brand asks for: dark letters on a
 * light surface, cream letters on a dark one. The "N" must not flip with it —
 * it sits on the orange ribbon, not on the page surface.
 */
object NexoBrandLockup {


        val Light: ImageVector by lazy {
        ImageVector.Builder(
            name = "NexoBrandLockup.Light",
            defaultWidth = 982.dp,
            defaultHeight = 434.dp,
            viewportWidth = 982f,
            viewportHeight = 434f,
        ).apply {
            group(
                translationX = -98f,
                translationY = -40f,
            ) {
                group {
                    group(
                        clipPathData = PathData {
                            moveTo(0f, 0f)
                            horizontalLineToRelative(512f)
                            verticalLineToRelative(512f)
                            horizontalLineTo(0f)
                            close()
                        },
                    ) {
                        group(
                            translationX = 135.593f,
                            translationY = 77.083f,
                        ) {
                            path(
                                fill = Brush.linearGradient(
                                    0f to Color(0xFFFFA620),
                                    0.351f to Color(0xFFF7781A),
                                    1f to Color(0xFFFF2D0F),
                                    start = Offset(-17.993f, -18.083f),
                                    end = Offset(258.807f, 376.917f),
                                ),
                            ) {
                                moveTo(35.217f, 0f)
                                horizontalLineToRelative(170.17f)
                                curveToRelative(19.566f, 0f, 35.427f, 15.861f, 35.427f, 35.427f)
                                verticalLineToRelative(323.512f)
                                lineToRelative(-120.407f, -74.448f)
                                lineTo(0f, 358.939f)
                                verticalLineTo(35.215f)
                                curveTo(0f, 15.766f, 15.767f, 0f, 35.217f, 0f)
                            }
                        }
                        group(
                            translationX = 135.593f,
                            translationY = 77.083f,
                        ) {
                            path(
                                stroke = Brush.linearGradient(
                                    0f to Color(0xFFFFA620),
                                    0.351f to Color(0xFFF7781A),
                                    1f to Color(0xFFFF2D0F),
                                    start = Offset(-17.993f, -18.083f),
                                    end = Offset(258.807f, 376.917f),
                                ),
                                strokeLineWidth = 36f,
                                strokeLineJoin = StrokeJoin.Round,
                            ) {
                                moveTo(35.217f, 0f)
                                horizontalLineToRelative(170.17f)
                                curveToRelative(19.566f, 0f, 35.427f, 15.861f, 35.427f, 35.427f)
                                verticalLineToRelative(323.512f)
                                lineToRelative(-120.407f, -74.448f)
                                lineTo(0f, 358.939f)
                                verticalLineTo(35.215f)
                                curveTo(0f, 15.766f, 15.767f, 0f, 35.217f, 0f)
                                close()
                            }
                        }
                    }
                }
                group {
                    group(
                        clipPathData = PathData {
                            moveTo(164.658f, 183.481f)
                            curveToRelative(0f, -22.521f, 18.257f, -40.778f, 40.778f, -40.778f)
                            horizontalLineToRelative(56.001f)
                            verticalLineToRelative(-73.4f)
                            horizontalLineToRelative(85.905f)
                            verticalLineToRelative(228.355f)
                            curveToRelative(0f, 22.521f, -18.257f, 40.777f, -40.778f, 40.777f)
                            horizontalLineToRelative(-58.72f)
                            verticalLineToRelative(138.644f)
                            horizontalLineToRelative(-83.186f)
                            close()
                        },
                    ) {
                        group {
                            group(
                                clipPathData = PathData {
                                    moveTo(0f, 0f)
                                    horizontalLineToRelative(512f)
                                    verticalLineToRelative(512f)
                                    horizontalLineTo(0f)
                                    close()
                                },
                            ) {
                                group(
                                    translationX = 181.788f,
                                    translationY = 153.73f,
                                ) {
                                    path(
                                        fill = Brush.linearGradient(
                                            0f to Color(0xFFEECCAE),
                                            1f to Color(0xFFFFF3E6),
                                            start = Offset(0f, 0f),
                                            end = Offset(0f, 182.684f),
                                        ),
                                    ) {
                                        moveTo(12f, 0f)
                                        horizontalLineToRelative(8.622f)
                                        curveToRelative(6.627f, 0f, 12f, 5.373f, 12f, 12f)
                                        verticalLineToRelative(149.51f)
                                        curveToRelative(0f, 4.907f, -2.987f, 9.32f, -7.543f, 11.142f)
                                        lineToRelative(-8.622f, 3.449f)
                                        curveTo(8.574f, 179.254f, 0f, 173.449f, 0f, 164.959f)
                                        verticalLineTo(12f)
                                        curveTo(0f, 5.373f, 5.373f, 0f, 12f, 0f)
                                    }
                                }
                            }
                        }
                        group {
                            group(
                                clipPathData = PathData {
                                    moveTo(0f, 0f)
                                    horizontalLineToRelative(512f)
                                    verticalLineToRelative(512f)
                                    horizontalLineTo(0f)
                                    close()
                                },
                            ) {
                                group(
                                    translationX = 183.267f,
                                    translationY = 159.57f,
                                ) {
                                    path(
                                        stroke = Brush.linearGradient(
                                            0f to Color(0xFFEECCAE),
                                            1f to Color(0xFFFFF3E6),
                                            start = Offset(-8.822f, -7.08f),
                                            end = Offset(-8.822f, 175.603f),
                                        ),
                                        strokeLineWidth = 14.4f,
                                        strokeLineJoin = StrokeJoin.Round,
                                    ) {
                                        moveToRelative(4.278f, 0.065f)
                                        lineToRelative(25.386f, 5.034f)
                                        verticalLineToRelative(152.545f)
                                        curveToRelative(0f, 3.239f, -1.97f, 6.152f, -4.976f, 7.358f)
                                        lineTo(0f, 174.908f)
                                        lineTo(0.247f, 3.37f)
                                        curveTo(0.25f, 1.245f, 2.194f, -0.348f, 4.278f, 0.065f)
                                        close()
                                    }
                                }
                            }
                        }
                        group {
                            group(
                                clipPathData = PathData {
                                    moveTo(0f, 0f)
                                    horizontalLineToRelative(512f)
                                    verticalLineToRelative(512f)
                                    horizontalLineTo(0f)
                                    close()
                                },
                            ) {
                                group(
                                    translationX = 304.147f,
                                    translationY = 145.965f,
                                ) {
                                    path(
                                        fill = Brush.linearGradient(
                                            0f to Color(0xFFD3B397),
                                            1f to Color(0xFFFFF3E6),
                                            start = Offset(0f, 182.684f),
                                            end = Offset(0f, 0f),
                                        ),
                                    ) {
                                        moveTo(0f, 13.049f)
                                        lineTo(31.853f, 0f)
                                        verticalLineToRelative(175.487f)
                                        curveToRelative(0f, 3.975f, -3.146f, 7.197f, -7.027f, 7.197f)
                                        horizontalLineTo(0f)
                                        close()
                                    }
                                }
                            }
                        }
                        group {
                            group(
                                clipPathData = PathData {
                                    moveTo(0f, 0f)
                                    horizontalLineToRelative(512f)
                                    verticalLineToRelative(512f)
                                    horizontalLineTo(0f)
                                    close()
                                },
                            ) {
                                group(
                                    translationX = 299.52f,
                                    translationY = 143.886f,
                                ) {
                                    path(
                                        stroke = Brush.linearGradient(
                                            0f to Color(0xFFD3B397),
                                            1f to Color(0xFFFFF3E6),
                                            start = Offset(5.413f, 184.763f),
                                            end = Offset(5.413f, 2.079f),
                                        ),
                                        strokeLineWidth = 14.4f,
                                        strokeLineJoin = StrokeJoin.Round,
                                    ) {
                                        moveTo(4.49f, 12.81f)
                                        lineTo(29.299f, 0f)
                                        verticalLineToRelative(175.478f)
                                        curveToRelative(0f, 3.719f, -3.414f, 6.502f, -7.057f, 5.751f)
                                        lineTo(0f, 176.643f)
                                        verticalLineTo(20.182f)
                                        curveToRelative(0f, -3.104f, 1.732f, -5.948f, 4.49f, -7.372f)
                                        close()
                                    }
                                }
                            }
                        }
                        group {
                            group(
                                clipPathData = PathData {
                                    moveTo(0f, 0f)
                                    horizontalLineToRelative(512f)
                                    verticalLineToRelative(512f)
                                    horizontalLineTo(0f)
                                    close()
                                },
                            ) {
                                path(
                                    fill = SolidColor(Color(0xFFFFF6EC)),
                                ) {
                                    moveTo(192.939f, 152.49f)
                                    horizontalLineToRelative(17.655f)
                                    curveToRelative(3.157f, 0f, 6.112f, 1.548f, 7.908f, 4.144f)
                                    lineToRelative(108.609f, 156.924f)
                                    curveToRelative(4.415f, 6.379f, -0.151f, 15.091f, -7.908f, 15.091f)
                                    horizontalLineToRelative(-16.189f)
                                    curveToRelative(-3.134f, 0f, -6.073f, -1.528f, -7.873f, -4.095f)
                                    lineTo(185.065f, 167.63f)
                                    curveToRelative(-4.47f, -6.373f, 0.089f, -15.14f, 7.874f, -15.14f)
                                }
                            }
                        }
                        group {
                            group(
                                clipPathData = PathData {
                                    moveTo(0f, 0f)
                                    horizontalLineToRelative(512f)
                                    verticalLineToRelative(512f)
                                    horizontalLineTo(0f)
                                    close()
                                },
                            ) {
                                path(
                                    stroke = SolidColor(Color(0xFFFFF6EC)),
                                    strokeLineWidth = 14.4f,
                                    strokeLineJoin = StrokeJoin.Round,
                                ) {
                                    moveTo(192.86f, 152.49f)
                                    horizontalLineToRelative(12.242f)
                                    curveToRelative(6.176f, 0.592f, 12.772f, 3.236f, 16.526f, 8.66f)
                                    lineToRelative(104.554f, 151.066f)
                                    curveToRelative(3.277f, 3.723f, 3.734f, 8.102f, 1.818f, 11.28f)
                                    curveToRelative(-1.916f, 3.177f, -6.205f, 5.153f, -10.429f, 5.153f)
                                    horizontalLineToRelative(-9.99f)
                                    curveToRelative(-5.997f, 0f, -11.618f, -2.923f, -15.062f, -7.833f)
                                    lineTo(185.02f, 167.566f)
                                    curveToRelative(-3.232f, -6.416f, -1.753f, -12.102f, 7.84f, -15.076f)
                                    close()
                                }
                            }
                        }
                    }
                }
                path(
                    fill = SolidColor(Color(0xFF0D1D3B)),
                ) {
                    moveTo(472f, 157.3f)
                    horizontalLineToRelative(102f)
                    verticalLineToRelative(28f)
                    horizontalLineToRelative(-74f)
                    verticalLineToRelative(38f)
                    horizontalLineToRelative(66f)
                    verticalLineToRelative(28f)
                    horizontalLineToRelative(-66f)
                    verticalLineToRelative(38f)
                    horizontalLineToRelative(74f)
                    verticalLineToRelative(28f)
                    horizontalLineTo(472f)
                    close()
                }
                group(
                    translationY = -2.7f,
                ) {
                    group(
                        clipPathData = PathData {
                            moveTo(600f, 160f)
                            horizontalLineToRelative(300f)
                            verticalLineToRelative(160f)
                            horizontalLineTo(600f)
                            close()
                        },
                    ) {
                        path(
                            stroke = SolidColor(Color(0xFF0D1D3B)),
                            strokeLineWidth = 30f,
                        ) {
                            moveToRelative(681.9f, 144f)
                            lineToRelative(121.2f, 192f)
                            moveToRelative(0f, -192f)
                            lineTo(681.9f, 336f)
                        }
                    }
                }
                path(
                    stroke = SolidColor(Color(0xFF0D1D3B)),
                    strokeLineWidth = 29f,
                ) {
                    moveTo(982.5f, 171.3f)
                    arcToRelative(63f, 66f, 0f, true, false, 0f, 132f)
                    arcToRelative(63f, 66f, 0f, true, false, 0f, -132f)
                    close()
                }
            }
        }.build()
    }



        val Dark: ImageVector by lazy {
        ImageVector.Builder(
            name = "NexoBrandLockup.Dark",
            defaultWidth = 982.dp,
            defaultHeight = 434.dp,
            viewportWidth = 982f,
            viewportHeight = 434f,
        ).apply {
            group(
                translationX = -98f,
                translationY = -40f,
            ) {
                group {
                    group(
                        clipPathData = PathData {
                            moveTo(0f, 0f)
                            horizontalLineToRelative(512f)
                            verticalLineToRelative(512f)
                            horizontalLineTo(0f)
                            close()
                        },
                    ) {
                        group(
                            translationX = 135.593f,
                            translationY = 77.083f,
                        ) {
                            path(
                                fill = Brush.linearGradient(
                                    0f to Color(0xFFFFA620),
                                    0.351f to Color(0xFFF7781A),
                                    1f to Color(0xFFFF2D0F),
                                    start = Offset(-17.993f, -18.083f),
                                    end = Offset(258.807f, 376.917f),
                                ),
                            ) {
                                moveTo(35.217f, 0f)
                                horizontalLineToRelative(170.17f)
                                curveToRelative(19.566f, 0f, 35.427f, 15.861f, 35.427f, 35.427f)
                                verticalLineToRelative(323.512f)
                                lineToRelative(-120.407f, -74.448f)
                                lineTo(0f, 358.939f)
                                verticalLineTo(35.215f)
                                curveTo(0f, 15.766f, 15.767f, 0f, 35.217f, 0f)
                            }
                        }
                        group(
                            translationX = 135.593f,
                            translationY = 77.083f,
                        ) {
                            path(
                                stroke = Brush.linearGradient(
                                    0f to Color(0xFFFFA620),
                                    0.351f to Color(0xFFF7781A),
                                    1f to Color(0xFFFF2D0F),
                                    start = Offset(-17.993f, -18.083f),
                                    end = Offset(258.807f, 376.917f),
                                ),
                                strokeLineWidth = 36f,
                                strokeLineJoin = StrokeJoin.Round,
                            ) {
                                moveTo(35.217f, 0f)
                                horizontalLineToRelative(170.17f)
                                curveToRelative(19.566f, 0f, 35.427f, 15.861f, 35.427f, 35.427f)
                                verticalLineToRelative(323.512f)
                                lineToRelative(-120.407f, -74.448f)
                                lineTo(0f, 358.939f)
                                verticalLineTo(35.215f)
                                curveTo(0f, 15.766f, 15.767f, 0f, 35.217f, 0f)
                                close()
                            }
                        }
                    }
                }
                group {
                    group(
                        clipPathData = PathData {
                            moveTo(164.658f, 183.481f)
                            curveToRelative(0f, -22.521f, 18.257f, -40.778f, 40.778f, -40.778f)
                            horizontalLineToRelative(56.001f)
                            verticalLineToRelative(-73.4f)
                            horizontalLineToRelative(85.905f)
                            verticalLineToRelative(228.355f)
                            curveToRelative(0f, 22.521f, -18.257f, 40.777f, -40.778f, 40.777f)
                            horizontalLineToRelative(-58.72f)
                            verticalLineToRelative(138.644f)
                            horizontalLineToRelative(-83.186f)
                            close()
                        },
                    ) {
                        group {
                            group(
                                clipPathData = PathData {
                                    moveTo(0f, 0f)
                                    horizontalLineToRelative(512f)
                                    verticalLineToRelative(512f)
                                    horizontalLineTo(0f)
                                    close()
                                },
                            ) {
                                group(
                                    translationX = 181.788f,
                                    translationY = 153.73f,
                                ) {
                                    path(
                                        fill = Brush.linearGradient(
                                            0f to Color(0xFFEECCAE),
                                            1f to Color(0xFFFFF3E6),
                                            start = Offset(0f, 0f),
                                            end = Offset(0f, 182.684f),
                                        ),
                                    ) {
                                        moveTo(12f, 0f)
                                        horizontalLineToRelative(8.622f)
                                        curveToRelative(6.627f, 0f, 12f, 5.373f, 12f, 12f)
                                        verticalLineToRelative(149.51f)
                                        curveToRelative(0f, 4.907f, -2.987f, 9.32f, -7.543f, 11.142f)
                                        lineToRelative(-8.622f, 3.449f)
                                        curveTo(8.574f, 179.254f, 0f, 173.449f, 0f, 164.959f)
                                        verticalLineTo(12f)
                                        curveTo(0f, 5.373f, 5.373f, 0f, 12f, 0f)
                                    }
                                }
                            }
                        }
                        group {
                            group(
                                clipPathData = PathData {
                                    moveTo(0f, 0f)
                                    horizontalLineToRelative(512f)
                                    verticalLineToRelative(512f)
                                    horizontalLineTo(0f)
                                    close()
                                },
                            ) {
                                group(
                                    translationX = 183.267f,
                                    translationY = 159.57f,
                                ) {
                                    path(
                                        stroke = Brush.linearGradient(
                                            0f to Color(0xFFEECCAE),
                                            1f to Color(0xFFFFF3E6),
                                            start = Offset(-8.822f, -7.08f),
                                            end = Offset(-8.822f, 175.603f),
                                        ),
                                        strokeLineWidth = 14.4f,
                                        strokeLineJoin = StrokeJoin.Round,
                                    ) {
                                        moveToRelative(4.278f, 0.065f)
                                        lineToRelative(25.386f, 5.034f)
                                        verticalLineToRelative(152.545f)
                                        curveToRelative(0f, 3.239f, -1.97f, 6.152f, -4.976f, 7.358f)
                                        lineTo(0f, 174.908f)
                                        lineTo(0.247f, 3.37f)
                                        curveTo(0.25f, 1.245f, 2.194f, -0.348f, 4.278f, 0.065f)
                                        close()
                                    }
                                }
                            }
                        }
                        group {
                            group(
                                clipPathData = PathData {
                                    moveTo(0f, 0f)
                                    horizontalLineToRelative(512f)
                                    verticalLineToRelative(512f)
                                    horizontalLineTo(0f)
                                    close()
                                },
                            ) {
                                group(
                                    translationX = 304.147f,
                                    translationY = 145.965f,
                                ) {
                                    path(
                                        fill = Brush.linearGradient(
                                            0f to Color(0xFFD3B397),
                                            1f to Color(0xFFFFF3E6),
                                            start = Offset(0f, 182.684f),
                                            end = Offset(0f, 0f),
                                        ),
                                    ) {
                                        moveTo(0f, 13.049f)
                                        lineTo(31.853f, 0f)
                                        verticalLineToRelative(175.487f)
                                        curveToRelative(0f, 3.975f, -3.146f, 7.197f, -7.027f, 7.197f)
                                        horizontalLineTo(0f)
                                        close()
                                    }
                                }
                            }
                        }
                        group {
                            group(
                                clipPathData = PathData {
                                    moveTo(0f, 0f)
                                    horizontalLineToRelative(512f)
                                    verticalLineToRelative(512f)
                                    horizontalLineTo(0f)
                                    close()
                                },
                            ) {
                                group(
                                    translationX = 299.52f,
                                    translationY = 143.886f,
                                ) {
                                    path(
                                        stroke = Brush.linearGradient(
                                            0f to Color(0xFFD3B397),
                                            1f to Color(0xFFFFF3E6),
                                            start = Offset(5.413f, 184.763f),
                                            end = Offset(5.413f, 2.079f),
                                        ),
                                        strokeLineWidth = 14.4f,
                                        strokeLineJoin = StrokeJoin.Round,
                                    ) {
                                        moveTo(4.49f, 12.81f)
                                        lineTo(29.299f, 0f)
                                        verticalLineToRelative(175.478f)
                                        curveToRelative(0f, 3.719f, -3.414f, 6.502f, -7.057f, 5.751f)
                                        lineTo(0f, 176.643f)
                                        verticalLineTo(20.182f)
                                        curveToRelative(0f, -3.104f, 1.732f, -5.948f, 4.49f, -7.372f)
                                        close()
                                    }
                                }
                            }
                        }
                        group {
                            group(
                                clipPathData = PathData {
                                    moveTo(0f, 0f)
                                    horizontalLineToRelative(512f)
                                    verticalLineToRelative(512f)
                                    horizontalLineTo(0f)
                                    close()
                                },
                            ) {
                                path(
                                    fill = SolidColor(Color(0xFFFFF6EC)),
                                ) {
                                    moveTo(192.939f, 152.49f)
                                    horizontalLineToRelative(17.655f)
                                    curveToRelative(3.157f, 0f, 6.112f, 1.548f, 7.908f, 4.144f)
                                    lineToRelative(108.609f, 156.924f)
                                    curveToRelative(4.415f, 6.379f, -0.151f, 15.091f, -7.908f, 15.091f)
                                    horizontalLineToRelative(-16.189f)
                                    curveToRelative(-3.134f, 0f, -6.073f, -1.528f, -7.873f, -4.095f)
                                    lineTo(185.065f, 167.63f)
                                    curveToRelative(-4.47f, -6.373f, 0.089f, -15.14f, 7.874f, -15.14f)
                                }
                            }
                        }
                        group {
                            group(
                                clipPathData = PathData {
                                    moveTo(0f, 0f)
                                    horizontalLineToRelative(512f)
                                    verticalLineToRelative(512f)
                                    horizontalLineTo(0f)
                                    close()
                                },
                            ) {
                                path(
                                    stroke = SolidColor(Color(0xFFFFF6EC)),
                                    strokeLineWidth = 14.4f,
                                    strokeLineJoin = StrokeJoin.Round,
                                ) {
                                    moveTo(192.86f, 152.49f)
                                    horizontalLineToRelative(12.242f)
                                    curveToRelative(6.176f, 0.592f, 12.772f, 3.236f, 16.526f, 8.66f)
                                    lineToRelative(104.554f, 151.066f)
                                    curveToRelative(3.277f, 3.723f, 3.734f, 8.102f, 1.818f, 11.28f)
                                    curveToRelative(-1.916f, 3.177f, -6.205f, 5.153f, -10.429f, 5.153f)
                                    horizontalLineToRelative(-9.99f)
                                    curveToRelative(-5.997f, 0f, -11.618f, -2.923f, -15.062f, -7.833f)
                                    lineTo(185.02f, 167.566f)
                                    curveToRelative(-3.232f, -6.416f, -1.753f, -12.102f, 7.84f, -15.076f)
                                    close()
                                }
                            }
                        }
                    }
                }
                path(
                    fill = SolidColor(Color(0xFFFFF6EC)),
                ) {
                    moveTo(472f, 157.3f)
                    horizontalLineToRelative(102f)
                    verticalLineToRelative(28f)
                    horizontalLineToRelative(-74f)
                    verticalLineToRelative(38f)
                    horizontalLineToRelative(66f)
                    verticalLineToRelative(28f)
                    horizontalLineToRelative(-66f)
                    verticalLineToRelative(38f)
                    horizontalLineToRelative(74f)
                    verticalLineToRelative(28f)
                    horizontalLineTo(472f)
                    close()
                }
                group(
                    translationY = -2.7f,
                ) {
                    group(
                        clipPathData = PathData {
                            moveTo(600f, 160f)
                            horizontalLineToRelative(300f)
                            verticalLineToRelative(160f)
                            horizontalLineTo(600f)
                            close()
                        },
                    ) {
                        path(
                            stroke = SolidColor(Color(0xFFFFF6EC)),
                            strokeLineWidth = 30f,
                        ) {
                            moveToRelative(681.9f, 144f)
                            lineToRelative(121.2f, 192f)
                            moveToRelative(0f, -192f)
                            lineTo(681.9f, 336f)
                        }
                    }
                }
                path(
                    stroke = SolidColor(Color(0xFFFFF6EC)),
                    strokeLineWidth = 29f,
                ) {
                    moveTo(982.5f, 171.3f)
                    arcToRelative(63f, 66f, 0f, true, false, 0f, 132f)
                    arcToRelative(63f, 66f, 0f, true, false, 0f, -132f)
                    close()
                }
            }
        }.build()
    }
}

