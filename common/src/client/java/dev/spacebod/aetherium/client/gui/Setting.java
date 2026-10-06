package dev.spacebod.aetherium.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * One row of the settings screen: a value with a getter and a setter, how to show and step it, and what to tell the
 * player about it. The screen stages changes and calls {@link #commit} on Apply, so every setting (vanilla, Aetherium,
 * the LOD mod's) behaves the same: pending values outlined, Undo, reset to default, restart notes.
 *
 * @param <T> the value type; the screen handles settings as {@code Setting<?>} and goes through the {@code Object}
 *            helpers below
 */
public final class Setting<T> {
	public enum Kind {
		TOGGLE, CHOICE, SLIDER, ACTION, INFO
	}

	/** A small label shown with the description (e.g. "Restart", "Lossless"). */
	public record Tag(String label, int color) {
		public static final Tag INSTANT = new Tag("Applies instantly", 0xFF5BD69B);
		public static final Tag RESTART = new Tag("Needs restart", 0xFFF2B35B);
		public static final Tag LOSSLESS = new Tag("Lossless", 0xFFB8AEFF);
		public static final Tag NEAR_LOSSLESS = new Tag("Near-lossless", 0xFFB8AEFF);
		public static final Tag EXPERIMENTAL = new Tag("Experimental", 0xFFFF8A6B);
		public static final Tag VULKAN = new Tag("Vulkan", 0xFF7FB5FF);
		public static final Tag SPEEDS_UP_CPU = new Tag("Saves CPU", 0xFF5BD69B);
		public static final Tag SPEEDS_UP_GPU = new Tag("Saves GPU", 0xFF5BD69B);
		public static final Tag SAVES_MEMORY = new Tag("Saves memory", 0xFF5BD69B);
	}

	public final String id;
	public final String name;
	public final String description;
	public final Kind kind;
	private final Supplier<T> get;
	private final Consumer<T> set;
	private @Nullable Supplier<T> defaults;
	private Function<T, String> label = String::valueOf;
	private Supplier<List<T>> values = List::of;
	private @Nullable ToDoubleFunction<T> toSlider;
	private @Nullable DoubleFunction<T> fromSlider;
	private @Nullable UnaryOperator<T> next;
	private @Nullable UnaryOperator<T> previous;
	private List<Tag> tags = List.of();
	private Supplier<@Nullable String> unavailable = () -> null;
	private BooleanSupplier restartPending = () -> false;
	private @Nullable Runnable afterApply;
	private boolean immediate;
	private int order;
	private String keywords = "";
	private String actionLabel = "";
	private @Nullable Runnable action;

	private Setting(String id, String name, String description, Kind kind, Supplier<T> get, Consumer<T> set) {
		this.id = id;
		this.name = name;
		this.description = description;
		this.kind = kind;
		this.get = get;
		this.set = set;
	}

	// ---------------------------------------------------------------- factories

	public static Setting<Boolean> toggle(String id, String name, String description, Supplier<Boolean> get, Consumer<Boolean> set) {
		Setting<Boolean> s = new Setting<>(id, name, description, Kind.TOGGLE, get, set);
		s.label = on -> on ? "On" : "Off";
		s.values = () -> List.of(Boolean.FALSE, Boolean.TRUE);
		return s;
	}

	public static <T> Setting<T> choice(String id, String name, String description, Supplier<List<T>> values, Supplier<T> get, Consumer<T> set,
										 Function<T, String> label) {
		Setting<T> s = new Setting<>(id, name, description, Kind.CHOICE, get, set);
		s.values = values;
		s.label = label;
		return s;
	}

	/** A slider over values mapped to [0, 1]; {@code next}/{@code previous} step it by keyboard, scroll and clicks. */
	public static <T> Setting<T> slider(String id, String name, String description, Supplier<T> get, Consumer<T> set, ToDoubleFunction<T> toSlider,
										 DoubleFunction<T> fromSlider, UnaryOperator<T> next, UnaryOperator<T> previous, Function<T, String> label) {
		Setting<T> s = new Setting<>(id, name, description, Kind.SLIDER, get, set);
		s.toSlider = toSlider;
		s.fromSlider = fromSlider;
		s.next = next;
		s.previous = previous;
		s.label = label;
		return s;
	}

	/** An integer slider from {@code min} to {@code max} in steps of {@code step}. */
	public static Setting<Integer> intSlider(String id, String name, String description, int min, int max, int step, Supplier<Integer> get,
											 Consumer<Integer> set, Function<Integer, String> label) {
		int span = Math.max(1, max - min);
		return slider(id, name, description, get, set,
				v -> (double) (v - min) / span,
				t -> Math.min(max, Math.max(min, min + (int) Math.round(t * span / step) * step)),
				v -> Math.min(max, v + step), v -> Math.max(min, v - step), label);
	}

	/** A row with a button on the right that runs {@code action}; holds no value. */
	public static Setting<String> action(String id, String name, String description, String buttonLabel, Runnable action) {
		Setting<String> s = new Setting<>(id, name, description, Kind.ACTION, () -> "", v -> {
		});
		s.actionLabel = buttonLabel;
		s.action = action;
		return s;
	}

	/** A read-only row: {@code value} is shown on the right. */
	public static Setting<String> info(String id, String name, String description, Supplier<String> value) {
		Setting<String> s = new Setting<>(id, name, description, Kind.INFO, value, v -> {
		});
		s.label = v -> v;
		return s;
	}

	// ---------------------------------------------------------------- builder

	public Setting<T> defaults(Supplier<T> defaults) {
		this.defaults = defaults;
		return this;
	}

	public Setting<T> tags(Tag... tags) {
		this.tags = List.of(tags);
		return this;
	}

	public Setting<T> tags(List<Tag> tags) {
		this.tags = List.copyOf(tags);
		return this;
	}

	/** While {@code reason} returns non-null the row is greyed out and clicking it shows the reason. */
	public Setting<T> unavailableWhen(Supplier<@Nullable String> reason) {
		this.unavailable = reason;
		return this;
	}

	/** True while the applied value only takes effect after a restart (shown in the screen's restart banner). */
	public Setting<T> restartPendingWhen(BooleanSupplier restartPending) {
		this.restartPending = restartPending;
		return this;
	}

	/** Runs once per Apply after every setting sharing this exact runnable was committed (e.g. one texture reload). */
	public Setting<T> afterApply(Runnable afterApply) {
		this.afterApply = afterApply;
		return this;
	}

	/** Changes apply as soon as they are made (presets that rewrite other settings). */
	public Setting<T> immediate() {
		this.immediate = true;
		return this;
	}

	/** Lower values commit first within one Apply. */
	public Setting<T> order(int order) {
		this.order = order;
		return this;
	}

	/** Extra words the search matches. */
	public Setting<T> keywords(String keywords) {
		this.keywords = keywords;
		return this;
	}

	// ---------------------------------------------------------------- queries (Object-typed for the screen)

	public Object applied() {
		return get.get();
	}

	public @Nullable Object defaultValue() {
		return defaults == null ? null : defaults.get();
	}

	@SuppressWarnings("unchecked")
	public void commit(Object value) {
		if (!Objects.equals(value, get.get())) {
			set.accept((T) value);
		}
	}

	@SuppressWarnings("unchecked")
	public String label(Object value) {
		return label.apply((T) value);
	}

	public List<Tag> tags() {
		return tags;
	}

	public @Nullable String unavailableReason() {
		return unavailable.get();
	}

	public boolean restartPending() {
		return restartPending.getAsBoolean();
	}

	public @Nullable Runnable afterApply() {
		return afterApply;
	}

	public boolean isImmediate() {
		return immediate;
	}

	public int order() {
		return order;
	}

	public String actionLabel() {
		return actionLabel;
	}

	public void runAction() {
		if (action != null) {
			action.run();
		}
	}

	/** The value {@code direction} steps (+1/-1) away from {@code value}; wraps for choices and toggles. */
	@SuppressWarnings("unchecked")
	public Object step(Object value, int direction) {
		return switch (kind) {
			case TOGGLE -> !(Boolean) value;
			case CHOICE -> {
				List<T> list = values.get();
				if (list.isEmpty()) {
					yield value;
				}
				int i = list.indexOf(value);
				yield list.get(Math.floorMod((i < 0 ? 0 : i) + direction, list.size()));
			}
			case SLIDER -> {
				UnaryOperator<T> op = direction > 0 ? next : previous;
				yield op == null ? value : op.apply((T) value);
			}
			default -> value;
		};
	}

	@SuppressWarnings("unchecked")
	public double sliderPosition(Object value) {
		return toSlider == null ? 0 : Math.max(0, Math.min(1, toSlider.applyAsDouble((T) value)));
	}

	public Object fromSlider(double t) {
		return fromSlider == null ? applied() : fromSlider.apply(Math.max(0, Math.min(1, t)));
	}

	public boolean matches(String query) {
		String q = query.toLowerCase(Locale.ROOT);
		return name.toLowerCase(Locale.ROOT).contains(q) || description.toLowerCase(Locale.ROOT).contains(q)
				|| keywords.toLowerCase(Locale.ROOT).contains(q) || id.toLowerCase(Locale.ROOT).contains(q);
	}

	/** A titled group of rows. */
	public record Section(String title, List<Setting<?>> settings) {
		public Section {
			settings = List.copyOf(settings);
		}
	}

	/** One page of the screen. {@code sections} is re-read every time the screen opens, so pages can depend on state. */
	public record Page(String id, String title, String subtitle, String icon, Supplier<List<Section>> sections) {
	}

	/** Collects sections without empty ones. */
	public static final class Sections {
		private final List<Section> list = new ArrayList<>();

		public Sections add(String title, List<? extends Setting<?>> settings) {
			if (!settings.isEmpty()) {
				list.add(new Section(title, new ArrayList<>(settings)));
			}
			return this;
		}

		public List<Section> build() {
			return List.copyOf(list);
		}
	}
}
