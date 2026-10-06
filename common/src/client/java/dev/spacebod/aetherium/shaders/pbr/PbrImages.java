package dev.spacebod.aetherium.shaders.pbr;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.function.IntUnaryOperator;

/**
 * Image work for material maps: scaling a map to its colour texture's size, and mip levels made per channel (a coded
 * channel must not be averaged across codes).
 */
public final class PbrImages {
	/** Every channel averaged. */
	public static final ChannelRule[] LINEAR = {ChannelRule.LINEAR, ChannelRule.LINEAR, ChannelRule.LINEAR, ChannelRule.LINEAR};

	private PbrImages() {
	}

	/** How four texels' values of one channel become the next mip level's. */
	@FunctionalInterface
	public interface ChannelRule {
		int blend(int v0, int v1, int v2, int v3);

		ChannelRule LINEAR = (v0, v1, v2, v3) -> (v0 + v1 + v2 + v3) / 4;

		/**
		 * Values grouped by {@code typeOf}: the group most of the four belong to (ties go to the earlier value) is
		 * averaged, the others ignored.
		 */
		static ChannelRule discrete(IntUnaryOperator typeOf) {
			return (v0, v1, v2, v3) -> {
				int t0 = typeOf.applyAsInt(v0), t1 = typeOf.applyAsInt(v1), t2 = typeOf.applyAsInt(v2), t3 = typeOf.applyAsInt(v3);
				int target = majority(t0, t1, t2, t3);
				int sum = 0, count = 0;
				if (t0 == target) {
					sum += v0;
					count++;
				}
				if (t1 == target) {
					sum += v1;
					count++;
				}
				if (t2 == target) {
					sum += v2;
					count++;
				}
				if (t3 == target) {
					sum += v3;
					count++;
				}
				return sum / count;
			};
		}

		private static int majority(int t0, int t1, int t2, int t3) {
			if (t0 != t1 && t0 != t2) {
				if (t2 == t3) {
					return t2;
				} else if (t0 != t3 && (t1 == t2 || t1 == t3)) {
					return t1;
				}
			}
			return t0;
		}
	}

	/**
	 * Mip levels 1..{@code maxLevel} from level 0, each texel from the four below it, channel by channel
	 * ({@code rules}: red, green, blue, alpha). Levels already made are kept.
	 */
	public static NativeImage[] mipLevels(NativeImage[] levels, int maxLevel, ChannelRule[] rules) {
		if (maxLevel + 1 <= levels.length) {
			return levels;
		}
		NativeImage[] out = new NativeImage[maxLevel + 1];
		out[0] = levels[0];
		for (int level = 1; level <= maxLevel; level++) {
			NativeImage previous = out[level - 1];
			NativeImage mip = new NativeImage(Math.max(1, previous.getWidth() >> 1), Math.max(1, previous.getHeight() >> 1), false);
			for (int x = 0; x < mip.getWidth(); x++) {
				for (int y = 0; y < mip.getHeight(); y++) {
					int x0 = Math.min(x * 2, previous.getWidth() - 1), x1 = Math.min(x * 2 + 1, previous.getWidth() - 1);
					int y0 = Math.min(y * 2, previous.getHeight() - 1), y1 = Math.min(y * 2 + 1, previous.getHeight() - 1);
					mip.setPixel(x, y, blend(previous.getPixel(x0, y0), previous.getPixel(x1, y0), previous.getPixel(x0, y1), previous.getPixel(x1, y1), rules));
				}
			}
			out[level] = mip;
		}
		return out;
	}

	/** Four ARGB texels blended channel by channel ({@code rules}: red, green, blue, alpha). */
	private static int blend(int c0, int c1, int c2, int c3, ChannelRule[] rules) {
		int r = rules[0].blend(c0 >> 16 & 255, c1 >> 16 & 255, c2 >> 16 & 255, c3 >> 16 & 255);
		int g = rules[1].blend(c0 >> 8 & 255, c1 >> 8 & 255, c2 >> 8 & 255, c3 >> 8 & 255);
		int b = rules[2].blend(c0 & 255, c1 & 255, c2 & 255, c3 & 255);
		int a = rules[3].blend(c0 >>> 24, c1 >>> 24, c2 >>> 24, c3 >>> 24);
		return a << 24 | r << 16 | g << 8 | b;
	}

	/** Each target texel takes the source texel under its centre. */
	public static NativeImage scaleNearest(NativeImage image, int width, int height) {
		NativeImage scaled = new NativeImage(image.format(), width, height, false);
		float xScale = (float) width / image.getWidth();
		float yScale = (float) height / image.getHeight();
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				scaled.setPixel(x, y, image.getPixel((int) ((x + 0.5f) / xScale), (int) ((y + 0.5f) / yScale)));
			}
		}
		return scaled;
	}

	/** Each target texel blends the (up to) four source texels around its centre. */
	public static NativeImage scaleBilinear(NativeImage image, int width, int height) {
		NativeImage scaled = new NativeImage(image.format(), width, height, false);
		float xScale = (float) width / image.getWidth();
		float yScale = (float) height / image.getHeight();
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				float sx = (x + 0.5f) / xScale;
				float sy = (y + 0.5f) / yScale;
				int x1 = Math.round(sx), y1 = Math.round(sy);
				int x0 = x1 - 1, y0 = y1 - 1;
				boolean x0ok = x0 >= 0, y0ok = y0 >= 0, x1ok = x1 < image.getWidth(), y1ok = y1 < image.getHeight();
				int colour;
				if (x0ok && y0ok && x1ok && y1ok) {
					float left = x1 + 0.5f - sx, right = sx - (x0 + 0.5f), top = y1 + 0.5f - sy, bottom = sy - (y0 + 0.5f);
					colour = mix(new int[]{image.getPixel(x0, y0), image.getPixel(x1, y0), image.getPixel(x0, y1), image.getPixel(x1, y1)},
							new float[]{left * top, right * top, left * bottom, right * bottom});
				} else if (x0ok && x1ok) {
					int row = y0ok ? y0 : y1;
					colour = mix(new int[]{image.getPixel(x0, row), image.getPixel(x1, row)}, new float[]{x1 + 0.5f - sx, sx - (x0 + 0.5f)});
				} else if (y0ok && y1ok) {
					int column = x0ok ? x0 : x1;
					colour = mix(new int[]{image.getPixel(column, y0), image.getPixel(column, y1)}, new float[]{y1 + 0.5f - sy, sy - (y0 + 0.5f)});
				} else {
					colour = image.getPixel(x0ok ? x0 : x1, y0ok ? y0 : y1);
				}
				scaled.setPixel(x, y, colour);
			}
		}
		return scaled;
	}

	/** Weighted sum of colours, channel by channel. */
	private static int mix(int[] colours, float[] weights) {
		int out = 0;
		for (int shift = 0; shift < 32; shift += 8) {
			float sum = 0;
			for (int i = 0; i < colours.length; i++) {
				sum += (colours[i] >>> shift & 255) * weights[i];
			}
			out |= (Math.round(sum) & 255) << shift;
		}
		return out;
	}
}
