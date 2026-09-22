#!/usr/bin/env bash
# =============================================================================
# build_ffmpeg.sh — 最小裁剪 FFmpeg 交叉编译（Android）
# 用法: ./build_ffmpeg.sh <ABI> [NDK_DIR]
#   ABI: arm64-v8a | armeabi-v7a | x86_64
#   NDK_DIR: NDK 根目录（默认从 ANDROID_NDK_HOME 读取）
#
# 产物: /tmp/ffmpeg-out/<ABI>/libffmpeg.so（重命名 ffmpeg 二进制，llvm-strip 后）
#       /tmp/ffmpeg-out/<ABI>/ffmpeg_version.txt（版本字符串）
#
# 最小化配置（容器级操作，无需编解码/滤镜/音频重采样）：
#   demuxer  flv, mpegts, hls, mov, mp4, aac, m4a, matroska, webm
#   muxer    mp4, adts, mpegts, segment, flv, matroska, webm, null
#   protocol file, http, https, tcp, tls, crypto, data
#   bsf      aac_adtstoasc, h264_mp4toannexb, hevc_mp4toannexb
#   parser   aac, h264, hevc
#   TLS      mbedtls（内建 AES，无需系统库）
# =============================================================================
set -euo pipefail

ABI=${1:-arm64-v8a}
NDK_ROOT=${2:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_HOME:-/opt/android}/ndk-r27}}}

FFMPEG_VERSION=${FFMPEG_VERSION:-n9.0.2}
MBEDTLS_VERSION=${MBEDTLS_VERSION:-mbedtls-3.6.2}

OUT_DIR=/tmp/ffmpeg-out/$ABI
SRC_DIR=/tmp/ffmpeg-src
MB_INSTALL=/tmp/ffmpeg-mbedtls-install

# ---------------------------------------------------------------------------
# 1. 查找 NDK toolchain
# ---------------------------------------------------------------------------
find_toolchain() {
  local ndk_dir="$1"
  # 查找 NDK toolchain：支持 ndk/21.4.7075529/toolchains/... 和 ndk-r27/toolchains/... 两种结构
  if [[ -d "$ndk_dir/toolchains" ]]; then
    echo "$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64"
  else
    for sub in "$ndk_dir"/*/toolchains/llvm/prebuilt/linux-x86_64; do
      if [[ -d "$sub" ]]; then
        echo "$sub"
        return 0
      fi
    done
  fi
}

NDK_DIR=""
for dir in "$NDK_ROOT" "$ANDROID_HOME/ndk"; do
  if [[ -d "$dir" ]]; then
    TC=$(find_toolchain "$dir")
    if [[ -n "$TC" && -d "$TC" ]]; then
      TOOLCHAIN="$TC"
      NDK_DIR="$dir"
      break
    fi
  fi
done

if [[ -z "${NDK_DIR:-}" ]]; then
  echo "ERROR: 未找到 NDK toolchain" >&2
  exit 1
fi

echo "NDK_DIR=$NDK_DIR"
echo "TOOLCHAIN=$TOOLCHAIN"

SYSROOT=$TOOLCHAIN/sysroot
API=26

# ---------------------------------------------------------------------------
# 2. ABI → 交叉编译参数
# ---------------------------------------------------------------------------
case "$ABI" in
  arm64-v8a)   ARCH=aarch64;  TARGET=aarch64-linux-android ;;
  armeabi-v7a) ARCH=arm;      TARGET=armv7a-linux-androideabi ;;
  x86_64)      ARCH=x86_64;   TARGET=x86_64-linux-android ;;
  *) echo "ERROR: 未知 ABI: $ABI" >&2; exit 1 ;;
esac

# API 级 wrapper 自带 --target=…-android26（app minSdk=26），裸 clang 会因缺 --target 链接失败
CC=$TOOLCHAIN/bin/${TARGET}${API}-clang
CXX=$TOOLCHAIN/bin/${TARGET}${API}-clang++

echo "Building ffmpeg $FFMPEG_VERSION for $ABI (arch=$ARCH, target=$TARGET)"

# ---------------------------------------------------------------------------
# 3. 构建 mbedtls（静态库，缓存）
# ---------------------------------------------------------------------------
if [[ ! -f $MB_INSTALL/lib/libmbedtls.a ]]; then
  echo "===== Build mbedtls $MBEDTLS_VERSION ====="
  mkdir -p /tmp/mbedtls-src $MB_INSTALL/lib $MB_INSTALL/include $MB_INSTALL/lib/pkgconfig

  # GitHub releases .tar.bz2 包含 framework submodule 内容（git archive tag 不含 submodule）
  MB_VER="${MBEDTLS_VERSION#mbedtls-}"
  curl -fsSL \
    "https://github.com/Mbed-TLS/mbedtls/releases/download/$MBEDTLS_VERSION/mbedtls-$MB_VER.tar.bz2" \
    -o /tmp/mbedtls.tar.bz2
  tar -xjf /tmp/mbedtls.tar.bz2 -C /tmp/mbedtls-src --strip-components=1

  cd /tmp/mbedtls-src

  # 确保 framework 子模块内容就位（make lib 依赖 framework/exported.make）
  git submodule update --init framework 2>/dev/null || true

  # 交叉编译静态库（必须用 NDK clang，否则产出 x86_64 目标文件与 aarch64 不兼容）
  CC="$CC" AR="$TOOLCHAIN/bin/llvm-ar" \
    CFLAGS="-fPIC -O2 --sysroot=$SYSROOT" \
    make -j$(nproc) clean lib
  cp library/*.a $MB_INSTALL/lib/
  cp -r include/* $MB_INSTALL/include/

  mkdir -p $MB_INSTALL/lib/pkgconfig
  cat > "$MB_INSTALL/lib/pkgconfig/mbedtls.pc" <<'PKGEOF'
prefix=/tmp/ffmpeg-mbedtls-install
exec_prefix=${prefix}
libdir=${exec_prefix}/lib
includedir=${prefix}/include
Name: mbedtls
Version: 3.6.2
Libs: -L${libdir} -lmbedtls -lmbedx509 -lmbedcrypto
Cflags: -I${includedir}
PKGEOF

  echo "mbedtls built"
fi

# ---------------------------------------------------------------------------
# 4. 克隆 ffmpeg（如未缓存）
# ---------------------------------------------------------------------------
FF_SRC=$SRC_DIR/ffmpeg
if [[ ! -d $FF_SRC/.git ]]; then
  echo "===== Fetch ffmpeg $FFMPEG_VERSION ====="
  mkdir -p $SRC_DIR
  # ls-remote 解引用 annotated tag → commit SHA，再按 SHA 浅 fetch
  # （git clone --branch 对 annotated tag 浅克隆会产生 "is not a commit" 问题）
  # 注意用完整 URL，此时 CWD 可能不在任何 git 仓库内
  FF_REPO_URL=https://github.com/FFmpeg/FFmpeg.git
  FF_SHA=$(git ls-remote "$FF_REPO_URL" "refs/tags/$FFMPEG_VERSION^{}" | awk '{print $1}')
  echo "ffmpeg $FFMPEG_VERSION → commit $FF_SHA"
  git init -q $FF_SRC
  cd $FF_SRC
  git remote add origin "$FF_REPO_URL"
  git fetch --depth=1 -q origin "$FF_SHA"
  git checkout -q --detach "$FF_SHA"
fi

cd $FF_SRC

# ---------------------------------------------------------------------------
# 5. 配置 ffmpeg
# ---------------------------------------------------------------------------
echo "===== Configure ffmpeg ====="

mkdir -p $OUT_DIR
export CC CXX SYSROOT PKG_CONFIG_PATH=$MB_INSTALL/lib/pkgconfig
export EXTRA_CFLAGS="-fPIC -O2 -I$MB_INSTALL/include"
export EXTRA_LDFLAGS="-L$MB_INSTALL/lib -lm -latomic -lmbedtls -lmbedx509 -lmbedcrypto"
export LD=$TOOLCHAIN/bin/ld.lld

./configure \
  --prefix=$OUT_DIR \
  --enable-cross-compile \
  --cross-prefix="$TOOLCHAIN/bin/$TARGET-" \
  --target-os=android \
  --arch=$ARCH \
  --cpu=generic \
  --cc=$CC \
  --cxx=$CXX \
  --ar=$TOOLCHAIN/bin/llvm-ar \
  --ranlib=$TOOLCHAIN/bin/llvm-ranlib \
  --strip=$TOOLCHAIN/bin/llvm-strip \
  --nm=$TOOLCHAIN/bin/llvm-nm \
  --pkg-config=pkg-config \
  --extra-cflags="$EXTRA_CFLAGS" \
  --extra-ldflags="$EXTRA_LDFLAGS" \
  --disable-autodetect \
  --enable-gpl \
  --enable-version3 \
  --disable-doc \
  --disable-ffprobe \
  --disable-ffplay \
  --enable-small \
  --disable-symver \
  --disable-everything \
  --enable-network \
  --enable-demuxer=flv,mpegts,hls,mov,mp4,aac,m4a,matroska,webm \
  --enable-muxer=mp4,adts,mpegts,segment,flv,matroska,webm,null \
  --enable-protocol=file,http,https,tcp,tls,crypto,data \
  --enable-bsf=aac_adtstoasc,h264_mp4toannexb,hevc_mp4toannexb \
  --enable-parser=aac,h264,hevc \
  --enable-mbedtls \
  --disable-openssl \
  --disable-gnutls \
  --disable-avdevice \
  --disable-avfilter \
  --disable-swresample \
  --disable-swscale \
  --disable-devices || {
  echo "===== configure FAILED, printing ffbuild/config.log tail ====="
  tail -100 $FF_SRC/ffbuild/config.log || true
  exit 1
}

# ---------------------------------------------------------------------------
# 6. 编译
# ---------------------------------------------------------------------------
echo "===== Make ffmpeg ====="
make -j$(nproc) V=1

# ---------------------------------------------------------------------------
# 7. 产物
# ---------------------------------------------------------------------------
cp ffmpeg $OUT_DIR/libffmpeg.so
$TOOLCHAIN/bin/llvm-strip -s $OUT_DIR/libffmpeg.so

echo "ffmpeg -version:"
$OUT_DIR/libffmpeg.so -version 2>&1 | head -3 > $OUT_DIR/ffmpeg_version.txt || true
cat $OUT_DIR/ffmpeg_version.txt

echo ""
echo "=== Artifact ==="
ls -lh $OUT_DIR/
echo "SUCCESS: $OUT_DIR/libffmpeg.so"