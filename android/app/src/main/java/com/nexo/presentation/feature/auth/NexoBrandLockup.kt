package com.nexo.presentation.feature.auth

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/**
 * The NEXO horizontal brand lockup (bookmark-ribbon mark + "NEXO" wordmark) as
 * [ImageVector]s with colors baked per theme.
 *
 * Two-color, theme-dependent art cannot go through [com.nexo.ui.icons.NexoIcons],
 * whose every icon is a single `SolidColor(Color.Black)` meant to be recolored by
 * `Icon(tint = ...)`. Here the fills are fixed, so the caller picks the export
 * matching the background:
 *
 * - [NexoBrandLockup.Light] for light surfaces (light export: navy ribbon + navy wordmark).
 * - [NexoBrandLockup.Dark] for dark surfaces (dark export: near-white ribbon + near-white wordmark).
 *
 * Source of truth: `brand/nexo-horizontal-light.svg` and `brand/nexo-horizontal-dark.svg`
 * (see `brand/README.md`). The two exports each have their own canvas and their own ribbon
 * geometry: the light canvas is `296.71 x 159.74` at viewBox origin `(659.2, 95.2)` and the dark
 * canvas is `285.91 x 144.15` at origin `(689, 160.99)`. Compose's `ImageVector` viewport is
 * always anchored at `(0, 0)` and cannot express an SVG viewBox origin, and the path data below is
 * kept verbatim in absolute userSpace coordinates — so each builder wraps its paths in a group
 * that translates by the negated viewBox origin. Without that group the lockup draws entirely
 * off-canvas. The leading `<rect>` of each SVG is the icon's background tile and is intentionally
 * dropped: on Android it is a surface color, not part of the mark. Generated from the brand
 * source; do not hand-edit the path data.
 */
object NexoBrandLockup {
    /** NEXO brand navy. */
    private val Navy = Color(0xFF0D1D3B)

    /** Wordmark ink on the dark export. */
    private val WordmarkOnDark = Color(0xFFEDF0F6)

    /**
     * Dark-export bookmark ribbon with the "N" knocked out (`fillType = EvenOdd`).
     */
    private const val MARK_PATH_DARK =
        "M778.826 167.684C779.014 168.775 779.138 199.238 779.098 235.384C779.034 298.139 " +
            "778.989 301.164 778.124 302.487C776.976 304.243 774.166 305.448 772.114 305.061C771.258 " +
            "304.903 769.235 303.807 767.622 302.627C763.185 299.394 746.633 287.757 740.198 283.344C737.072 " +
            "281.203 734.173 279.541 733.767 279.651C733.362 279.764 724.898 285.461 714.97 292.304C704.958 " +
            "299.205 696.227 304.888 695.366 305.056C693.467 305.433 691.087 304.297 689.871 302.433C689.049 " +
            "301.179 689 297.217 689 234.144C689 167.208 689 167.188 690.034 165.205C690.642 164.035 691.859 " +
            "162.771 693.002 162.116C694.936 161.006 694.936 161.006 733.911 161.001C771.53 160.991 772.95 " +
            "161.026 774.77 161.953C777.031 163.108 778.371 165.002 778.826 167.684ZM758.006 223.012C758.006 " +
            "198.163 758.006 198.163 756.373 196.377C754.894 194.761 754.499 194.593 752.129 194.593C749.839 " +
            "194.593 749.305 194.796 747.83 196.229C746.149 197.86 746.149 197.86 746.015 212.457L745.886 " +
            "227.059L734.084 213.483C717.379 194.28 717.814 194.707 714.93 194.707C713.115 194.707 712.225 " +
            "195.004 711.052 195.991C709.529 197.275 709.529 197.275 709.529 222.716C709.529 248.151 709.529 " +
            "248.151 711.275 249.693C714.07 252.147 718.418 251.418 720.396 248.161C721.336 246.624 721.396 " +
            "245.667 721.415 232.884C721.435 219.25 721.435 219.25 723.518 221.714L729.746 228.903L740.133 " +
            "240.817C749.587 251.691 749.062 251.23 752.07 251.23C754.335 251.23 754.845 251.026 756.324 " +
            "249.545C758.006 247.858 758.006 247.858 758.006 223.012Z"

    /**
     * Light-export bookmark ribbon with the "N" knocked out (`fillType = EvenOdd`).
     */
    private const val MARK_PATH_LIGHT =
        "M724.387 100.001C735.321 100.006 741.837 100.033 745.835 100.126C751.001 100.246 752.513 100.456 754" +
            ".131 101.279L754.135 101.281C757.307 102.902 759.184 105.644 759.784 109.182H759.782C759.835 109.491" +
            " 759.85 109.906 759.858 110.111C759.871 110.429 759.882 110.85 759.893 111.36C759.914 112.384 759.93" +
            "4 113.817 759.953 115.607C759.991 119.191 760.023 124.239 760.048 130.376C760.097 142.653 760.118 15" +
            "9.31 760.099 177.387C760.066 208.751 760.039 225.224 759.911 234.016C759.848 238.389 759.759 240.956" +
            " 759.621 242.53C759.487 244.066 759.281 245.142 758.635 246.13L758.634 246.129C757.652 247.63 756.12" +
            "4 248.699 754.64 249.335C753.159 249.969 751.332 250.339 749.568 250.01V250.011C749.566 250.01 749.5" +
            "64 250.009 749.562 250.009C749.561 250.009 749.56 250.009 749.559 250.009V250.008C748.619 249.832 74" +
            "7.534 249.304 746.723 248.864C745.796 248.362 744.774 247.723 743.855 247.051C739.448 243.84 722.923" +
            " 232.222 716.502 227.819L715.386 227.068C714.292 226.347 713.281 225.721 712.469 225.255C712.311 225" +
            ".164 712.163 225.085 712.028 225.011C711.924 225.078 711.813 225.148 711.696 225.225C710.708 225.87 " +
            "709.314 226.798 707.614 227.942C704.218 230.228 699.63 233.357 694.673 236.774C689.651 240.236 684.9" +
            "42 243.397 681.372 245.72C679.59 246.879 678.075 247.84 676.939 248.528C676.375 248.87 675.878 249.1" +
            "62 675.477 249.38C675.278 249.488 675.072 249.594 674.877 249.685C674.757 249.74 674.388 249.911 673" +
            ".949 249.998C670.635 250.655 667.12 248.767 665.361 246.075C664.691 245.052 664.516 243.852 664.397 " +
            "242.338C664.269 240.694 664.185 238.017 664.128 233.509C664.013 224.453 664 207.669 664 176.145C664 " +
            "142.694 664 125.922 664.13 117.272C664.194 112.98 664.291 110.563 664.454 109.074C664.538 108.311 66" +
            "4.649 107.679 664.825 107.107C665.011 106.503 665.239 106.077 665.374 105.818C666.249 104.137 667.87" +
            "2 102.451 669.51 101.513V101.514C670.145 101.149 670.848 100.761 672.205 100.521C673.331 100.321 674" +
            ".934 100.215 677.484 100.146C682.651 100.005 692.453 100.003 711.91 100.001H724.387ZM730.129 139.593" +
            "C728.968 139.593 728.772 139.666 728.74 139.678C728.739 139.678 728.733 139.68 728.721 139.688C728.7" +
            "04 139.697 728.668 139.719 728.605 139.766C728.467 139.87 728.265 140.046 727.922 140.38L727.919 140" +
            ".383C727.632 140.662 727.538 140.765 727.513 140.794C727.493 140.842 727.404 141.089 727.323 141.814" +
            "C727.118 143.646 727.082 147.076 727.015 154.484L726.887 169.086L726.816 177.002L721.622 171.027L709" +
            ".819 157.451V157.45C701.405 147.778 697.452 143.221 695.224 141.036C694.15 139.983 693.741 139.757 6" +
            "93.65 139.716C693.646 139.715 693.641 139.715 693.636 139.715C693.508 139.704 693.391 139.707 692.93" +
            "1 139.707C692.165 139.707 691.853 139.774 691.694 139.826C691.573 139.866 691.387 139.947 690.983 14" +
            "0.286C690.969 140.298 690.956 140.309 690.944 140.319C690.885 140.576 690.788 141.172 690.714 142.46" +
            "5C690.53 145.695 690.528 151.931 690.528 164.716C690.528 177.506 690.53 183.724 690.74 186.971C690.8" +
            "38 188.481 690.972 189.049 691.036 189.242C691.085 189.288 691.144 189.342 691.254 189.438C692.581 1" +
            "90.604 694.776 190.342 695.832 188.604L695.837 188.596C695.982 188.358 696.017 188.276 696.056 188.1" +
            "33C696.116 187.913 696.195 187.481 696.258 186.541C696.384 184.634 696.405 181.324 696.415 174.88C69" +
            "6.42 171.483 696.424 168.895 696.46 166.983C696.494 165.149 696.559 163.694 696.745 162.707C696.818 " +
            "162.321 697.003 161.393 697.662 160.621C698.073 160.14 698.722 159.659 699.61 159.509C700.448 159.36" +
            "7 701.138 159.581 701.557 159.771C702.28 160.101 702.797 160.642 703.002 160.857C703.267 161.136 703" +
            ".549 161.47 703.784 161.749H703.785L710.007 168.932L720.395 180.846L720.397 180.849C725.238 186.417 " +
            "727.268 188.775 728.49 189.841C728.776 190.09 728.931 190.186 728.992 190.219C729.02 190.222 729.075" +
            " 190.228 729.17 190.23C729.405 190.237 729.597 190.23 730.069 190.23C730.61 190.23 730.935 190.217 7" +
            "31.158 190.192C731.358 190.171 731.403 190.147 731.386 190.154C731.352 190.168 731.47 190.156 732.2 " +
            "189.425C732.407 189.218 732.463 189.156 732.488 189.125C732.489 189.123 732.49 189.122 732.49 189.12" +
            "C732.556 188.945 732.699 188.399 732.802 186.773C733.004 183.58 733.006 177.506 733.006 165.013C733." +
            "006 152.52 733.004 146.44 732.808 143.229C732.708 141.6 732.569 141.022 732.493 140.812C732.483 140." +
            "783 732.547 140.826 732.159 140.401V140.4C731.691 139.889 731.481 139.705 731.411 139.647C731.382 13" +
            "9.642 731.337 139.635 731.272 139.628C731.046 139.604 730.711 139.593 730.129 139.593Z"

    /** "NEXO" wordmark, dark export (`#EDF0F6`). */    private const val WORDMARK_DARK_PATH =
        "M803.302 249V202.455H833.575V209.523H811.734V222.159H832.007V229.227H811.734V241.932H833.757V249H803.302Z" +
            "M873.543 202.455L883.793 219.5H884.156L894.452 202.455H904.065L889.724 225.727L904.293 249H894.52L884.156 " +
            "232.068H883.793L873.429 249H863.702L878.406 225.727L863.884 202.455H873.543Z" +
            "M974.908 225.727C974.908 230.742 973.969 235.038 972.09 238.614C970.226 242.174 967.681 244.902 964.454 " +
            "246.795C961.241 248.689 957.597 249.636 953.522 249.636C949.446 249.636 945.794 248.689 942.567 " +
            "246.795C939.355 244.886 936.81 242.152 934.931 238.591C933.067 235.015 932.135 230.727 932.135 " +
            "225.727C932.135 220.712 933.067 216.424 934.931 212.864C936.81 209.288 939.355 206.553 942.567 " +
            "204.659C945.794 202.765 949.446 201.818 953.522 201.818C957.597 201.818 961.241 202.765 964.454 " +
            "204.659C967.681 206.553 970.226 209.288 972.09 212.864C973.969 216.424 974.908 220.712 974.908 " +
            "225.727ZM966.431 225.727C966.431 222.197 965.878 219.22 964.772 216.795C963.681 214.356 962.166 " +
            "212.515 960.226 211.273C958.287 210.015 956.052 209.386 953.522 209.386C950.991 209.386 948.757 " +
            "210.015 946.817 211.273C944.878 212.515 943.355 214.356 942.249 216.795C941.158 219.22 940.613 " +
            "222.197 940.613 225.727C940.613 229.258 941.158 232.242 942.249 234.682C943.355 237.106 944.878 " +
            "238.947 946.817 240.205C948.757 241.447 950.991 242.068 953.522 242.068C956.052 242.068 958.287 " +
            "241.447 960.226 240.205C962.166 238.947 963.681 237.106 964.772 234.682C965.878 232.242 966.431 " +
            "229.258 966.431 225.727Z"

    /** "NEXO" wordmark, light export (`#0D1D3B`). */
    private const val WORDMARK_LIGHT_PATH =
        "M784.302 191V144.455H814.575V151.523H792.734V164.159H813.007V171.227H792.734V183.932H814.757V191H784.302Z" +
            "M854.543 144.455L864.793 161.5H865.156L875.452 144.455H885.065L870.724 167.727L885.293 191H875.52L865.156 " +
            "174.068H864.793L854.429 191H844.702L859.406 167.727L844.884 144.455H854.543Z" +
            "M955.908 167.727C955.908 172.742 954.969 177.038 953.09 180.614C951.226 184.174 948.681 186.902 945.454 " +
            "188.795C942.241 190.689 938.597 191.636 934.522 191.636C930.446 191.636 926.794 190.689 923.567 " +
            "188.795C920.355 186.886 917.81 184.152 915.931 180.591C914.067 177.015 913.135 172.727 913.135 " +
            "167.727C913.135 162.712 914.067 158.424 915.931 154.864C917.81 151.288 920.355 148.553 923.567 " +
            "146.659C926.794 144.765 930.446 143.818 934.522 143.818C938.597 143.818 942.241 144.765 945.454 " +
            "146.659C948.681 148.553 951.226 151.288 953.09 154.864C954.969 158.424 955.908 162.712 955.908 " +
            "167.727ZM947.431 167.727C947.431 164.197 946.878 161.22 945.772 158.795C944.681 156.356 943.166 " +
            "154.515 941.226 153.273C939.287 152.015 937.052 151.386 934.522 151.386C931.991 151.386 929.757 " +
            "152.015 927.817 153.273C925.878 154.515 924.355 156.356 923.249 158.795C922.158 161.22 921.613 " +
            "164.197 921.613 167.727C921.613 171.258 922.158 174.242 923.249 176.682C924.355 179.106 925.878 " +
            "180.947 927.817 182.205C929.757 183.447 931.991 184.068 934.522 184.068C937.052 184.068 939.287 " +
            "183.447 941.226 182.205C943.166 180.947 944.681 179.106 945.772 176.682C946.878 174.242 947.431 " +
            "171.258 947.431 167.727Z"

    /**
     * Light-background export: navy mark + navy wordmark.
     *
     * The light SVG viewBox is `659.2 95.2 296.71 159.74`. Compose has no viewBox min-x/min-y,
     * so the viewport is the viewBox *size* and a wrapping group shifts the absolute-coordinate
     * paths back to the origin. See the group in the builder.
     */
    val Light: ImageVector =
        ImageVector.Builder(
            name = "NexoBrandLockup.Light",
            defaultWidth = 190.dp,
            defaultHeight = 96.dp,
            viewportWidth = 296.71f,
            viewportHeight = 159.74f,
        ).apply {
            // The SVG viewBox is "659.2 95.2 296.71 159.74", but a Compose ImageVector
            // viewport is always anchored at (0,0) and has no min-x/min-y. The paths keep
            // their absolute userSpace coordinates, so the group cancels the viewBox origin;
            // without it every path lands ~2.2 viewport widths off the right edge.
            group(translationX = -659.2f, translationY = -95.2f) {
                filledPath(Navy, MARK_PATH_LIGHT, evenOdd = true)
                filledPath(Navy, WORDMARK_LIGHT_PATH)
            }
        }.build()

    /**
     * Dark-background export: near-white ribbon + near-white wordmark.
     *
     * The ribbon and the wordmark share the same light ink, so the "N" knocked out of the
     * ribbon (`evenOdd`) reads as the dark surface behind the lockup. The dark SVG viewBox is
     * `689 160.99 285.91 144.15`. Same viewBox-origin cancellation as the light export,
     * carrying the dark export's own ribbon geometry.
     */
    val Dark: ImageVector =
        ImageVector.Builder(
            name = "NexoBrandLockup.Dark",
            defaultWidth = 190.dp,
            defaultHeight = 96.dp,
            viewportWidth = 285.91f,
            viewportHeight = 144.15f,
        ).apply {
            // Dark SVG viewBox is "689 160.99 285.91 144.15"; same viewBox-origin cancellation
            // as the light export. See the Light builder.
            group(translationX = -689f, translationY = -160.99f) {
                filledPath(WordmarkOnDark, MARK_PATH_DARK, evenOdd = true)
                filledPath(WordmarkOnDark, WORDMARK_DARK_PATH)
            }
        }.build()

    /**
     * Filled path from a raw SVG `pathData` string, parsed via [PathParser] so the
     * curves survive verbatim. Unlike [com.nexo.ui.icons.NexoIcons] the fill is fixed
     * per call, since the lockup's two inks cannot be recolored by `Icon(tint = ...)`.
     */
    private fun ImageVector.Builder.filledPath(
        fill: Color,
        pathData: String,
        evenOdd: Boolean = false,
    ): ImageVector.Builder =
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(fill),
            pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
        )
}
