package net.sandius.rembulan.compiler.types;

import java.util.Objects;

public class BaseType extends ConcreteType {

	private final String name;
	private final String shortName;

	protected BaseType(ConcreteType supertype, String name, String shortName) {
		super(supertype);
		this.name = Objects.requireNonNull(name);
		this.shortName = Objects.requireNonNull(shortName);
	}

	protected BaseType(String name, String shortName) {
		this(null, name, shortName);
	}

	@Override
	public String toString() {
		return shortName;
	}

	public BaseType newSubtype(String name, String tag) {
		return new BaseType(this, name, tag);
	}

}
