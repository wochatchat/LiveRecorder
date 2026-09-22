#!/usr/bin/env bash
# =============================================================================
# build_ffmpeg.sh — 最小裁剪 FFmpeg 交叉编译（Android）
#
# 输入: $1 = ABI (arm64-v8a | armeabi-v7a | x86_64)
# 环境: ANDROID_HOME, ANDROID_NDK_HOME (或 ANDROID_NDK_ROOT)
#
# 产物: $OUT_DIR/libffmpeg.so（重命名的 ffmpeg 可执行文件，strip 后）
#       $OUT_DIR/ffmpeg_version.txt（版本字符串，对照用）
#
# 最小化配置目标（全部是容器级操作，无需编解码）：
#   demuxer  flv, mpegts, hls, mov, aac, matroska
#   muxer    mp4, adts, mpegts, segment, matroska, flv
#   parser   aac, h264, hevc
#   bsf      aac_adtstoasc, h264_mp4toannexb, hevc_mp4toannexb
#   protocol file, http, https, tcp, tls, crypto, data
#   tls 后端 mbedtls（内建 AES/RC4，无额外系统依赖）
# =============================================================================
set -euo pipefail

ABI=${1:-arm64-v8a}
BUILD_DIR=${BUILD_DIR:-/tmp/ffmpeg-build/$ABI}
SRC_DIR=$BUILD_DIR/src
OUT_DIR=$BUILD_DIR/out

FFMPEG_VERSION=${FFMPEG_VERSION:-n9.0.2}
MBEDTLS_VERSION=${MBEDTLS_VERSION:-yotta-2.3.2}

# ---------------------------------------------------------------------------
# NDK 工具链路径（NDK r27 或更早 r27.x）
# ---------------------------------------------------------------------------
ANDROID_HOME=${ANDROID_HOME:-${ANDROID_NDK_ROOT:-$ANDROID_NDK_HOME}}
if [[ -z "$ANDROID_HOME" ]]; then
    echo "::error:: ANDROID_HOME / ANDROID_NDK_HOME 未设置"
    exit 1
fi

NDK_PATH=$(ls -d $ANDROID_HOME/ndk/*/toolchains/llvm/prebuilt/linux-x86_64 2>/dev/null | head -1)
if [[ -z "$NDK_PATH" ]]; then
    echo "::error:: 未找到 NDK toolchain ($ANDROID_HOME/ndk/*/toolchains)"
    exit 1
fi
echo "NDK toolchain: $NDK_PATH"

SYSROOT=$NDK_PATH/../sysroot
CC=$NDK_PATH/bin/clang
CXX=$NDK_PATH/bin/clang++

# ---------------------------------------------------------------------------
# ABI → 交叉编译参数映射
# ---------------------------------------------------------------------------
case "$ABI" in
    arm64-v8a)
        ARCH=aarch64
        TARGET=aarch64-linux-android
        MIN_API=26
        ;;
    armeabi-v7a)
        ARCH=arm
        TARGET=armv7a-linux-androideabi
        MIN_API=26
        ;;
    x86_64)
        ARCH=x86_64
        TARGET=x86_64-linux-android
        MIN_API=26
        ;;
    *)
        echo "::error:: 未知 ABI: $ABI"
        exit 1
        ;;
esac

TARGET_API=$MIN_API

# pkg-config 路径（mbedtls 用）
PKG_CONFIG_PATH=$BUILD_DIR/mbedtls-installed/lib/pkgconfig
export PKG_CONFIG_PATH

# ---------------------------------------------------------------------------
# 下载源码（缓存：已解压则跳过）
# ---------------------------------------------------------------------------
download_and_extract() {
    local name=$1 version=$2 url=$3
    local src=$SRC_DIR/$name
    if [[ -d $src ]]; then
        echo "已存在源码目录: $src，跳过下载"
        return
    fi
    mkdir -p "$SRC_DIR"
    local archive=$BUILD_DIR/cache/$(basename "$url")
    mkdir -p $(dirname "$archive")
    if [[ ! -f $archive ]]; then
        echo "下载 $name $version ..."
        curl -fsSL "$url" -o "$archive"
    else
        echo "命中缓存: $archive"
    fi
    echo "解压到 $src ..."
    mkdir -p "$src"
    tar -xf "$archive" -C "$SRC_DIR" --strip-components=1 -C "$src"
}

download_and_extract ffmpeg "$FFMPEG_VERSION" \
    "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/$FFMPEG_VERSION.tar.gz"
download_and_extract mbedtls "$MBEDTLS_VERSION" \
    "https://github.com/Mbed-TLS/mbedtls/archive/refs/tags/$MBEDTLS_VERSION.tar.gz"

# ---------------------------------------------------------------------------
# 构建 mbedtls（静态库，仅 libmbedtls + libmbedx509 + libmbedcrypto）
# ---------------------------------------------------------------------------
MBEDTLS_INSTALL=$BUILD_DIR/mbedtls-installed
if [[ ! -f $MBEDTLS_INSTALL/lib/libmbedtls.a ]]; then
    echo "===== 构建 mbedtls ====="
    cd "$SRC_DIR/mbedtls"
    make -j$(nproc) clean 2>/dev/null || true
    CFLAGS="-fPIC -O2" make -j$(nproc) lib
    make -j$(nproc) install DESTDIR="$MBEDTLS_INSTALL"
    # pkg-config 手动创建（make install 不会生成）
    cat > "$MBEDTLS_INSTALL/lib/pkgconfig/mbedtls.pc" <<'PKGEOF'
prefix=/usr/local
exec_prefix=${prefix}
libdir=${exec_prefix}/lib
includedir=${prefix}/include

Name: mbedtls
Description: mbed TLS library
Version: 3.6.2
Libs: -L${libdir} -lmbedtls -lmbedx509 -lmbedcrypto
Cflags: -I${includedir}
PKGEOF
    echo "mbedtls 安装完成"
fi

# ---------------------------------------------------------------------------
# 构建 ffmpeg
# ---------------------------------------------------------------------------
FFMPEG_BUILD=$SRC_DIR/ffmpeg-build
mkdir -p "$FFMPEG_BUILD"
cd "$FFMPEG_BUILD"

if [[ -f config.h ]] && [[ ! "$FORCE_REBUILD" ]]; then
    echo "ffmpeg 已配置，跳过重新配置（设 FORCE_REBUILD=1 强制）"
else
    echo "===== 配置 ffmpeg ($FFMPEG_VERSION) for $ABI ====="

    # 最小化配置：只启用容器级功能，禁用编解码/滤镜/音频重采样
    #
    # --disable-autodetect        不自动探测系统库
    # --enable-mbedtls            TLS 后端
    # --disable-doc               不生成文档
    # --disable-programs          不构建辅助工具（ffprobe 等）
    # --enable-small              优化体积
    # --disable-symver            禁用符号版本（简化链接）
    # --disable-static            不生成 .a（只产出可执行文件）
    # --enable-shared             不需要（exe）
    $SRC_DIR/ffmpeg/configure \
        --prefix=$OUT_DIR \
        --enable-cross-compile \
        --cross-prefix="$NDK_PATH/bin/${TARGET}" \
        --target-os=android \
        --arch="$ARCH" \
        --cpu=generic \
        --sysroot="$SYSROOT" \
        --cc="$CC" \
        --cxx="$CXX" \
        --ar="$NDK_PATH/bin/llvm-ar" \
        --ranlib="$NDK_PATH/bin/llvm-ranlib" \
        --strip="$NDK_PATH/bin/llvm-strip" \
        --nm="$NDK_PATH/bin/llvm-nm" \
        \
        --pkg-config="pkg-config" \
        --pkg-config-flags="--static" \
        \
        --extra-cflags="-fPIC -O2 -I$MBEDTLS_INSTALL/include" \
        --extra-ldflags="-L$MBEDTLS_INSTALL/lib -lm -latomic" \
        --extra-libs="-lmbedtls -lmbedx509 -lmbedcrypto -lm" \
        \
        --disable-autodetect \
        --enable-gpl \
        --enable-version3 \
        --disable-doc \
        --disable-programs \
        --enable-small \
        --disable-symver \
        \
        --disable-everything \
        \
        --enable-network \
        --enable-dxVA2Proc \
        \
        --enable-demuxer=flv,mpegts,hls,mov,mp4,aac,m4a,matroska,webm \
        --enable-muxer=mp4,adts,mpegts,segment,flv,matroska,webm,null \
        --enable-protocol=file,http,https,tcp,tls,crypto,data \
        --enable-bsf=aac_adtstoasc,h264_mp4toannexb,hevc_mp4toannexb \
        --enable-parser=aac,h264,hevc \
        \
        --enable-mbedtls \
        --disable-openssl \
        --disable-gnutls \
        --disable-libtls \
        \
        --disable-avdevice \
        --disable-avfilter \
        --disable-swresample \
        --disable-swscale \
        --disable-postproc \
        --disable-avformat \
        --enable-avformat \
        --disable-devices \
        2>&1 | tail -5
fi

echo "===== 编译 ffmpeg ====="
make -j$(nproc) V=1

echo "===== 产物重命名 libffmpeg.so ====="
mkdir -p "$OUT_DIR"
cp ffmpeg "$OUT_DIR/libffmpeg.so"
$NDK_PATH/bin/llvm-strip -s "$OUT_DIR/libffmpeg.so"

echo "ffmpeg 版本:"
$OUT_DIR/libffmpeg.so -version 2>&1 | head -3 > "$OUT_DIR/ffmpeg_version.txt"
cat "$OUT_DIR/ffmpeg_version.txt"

ls -lh "$OUT_DIR/"
echo "===== DONE: $OUT_DIR/libffmpeg.so ====="