package net.sandius.rembulan.compiler.gen;

import net.sandius.rembulan.compiler.gen.block.AccountingNode;
import net.sandius.rembulan.compiler.gen.block.Entry;
import net.sandius.rembulan.compiler.gen.block.LineInfo;
import net.sandius.rembulan.compiler.gen.block.Linear;
import net.sandius.rembulan.compiler.gen.block.LinearSeq;
import net.sandius.rembulan.compiler.gen.block.LinearSeqTransformation;
import net.sandius.rembulan.compiler.gen.block.Node;
import net.sandius.rembulan.compiler.gen.block.NodeVisitor;
import net.sandius.rembulan.compiler.gen.block.Nodes;
import net.sandius.rembulan.compiler.gen.block.SlotEffect;
import net.sandius.rembulan.compiler.gen.block.Target;
import net.sandius.rembulan.compiler.gen.block.UnconditionalJump;
import net.sandius.rembulan.lbc.Prototype;
import net.sandius.rembulan.util.IntVector;
import net.sandius.rembulan.util.ReadOnlyArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FlowIt {

	public final Prototype prototype;

	public Map<Node, Edges> reachabilityGraph;
	public Map<Node, InOutSlots> slots;

	public FlowIt(Prototype prototype) {
		this.prototype = prototype;
	}

	public void go() {
		IntVector code = prototype.getCode();
		Target[] targets = new Target[code.length()];
		for (int pc = 0; pc < targets.length; pc++) {
			targets[pc] = new Target(Integer.toString(pc + 1));
		}

		ReadOnlyArray<Target> pcLabels = ReadOnlyArray.wrap(targets);

		LuaInstructionToNodeTranslator translator = new LuaInstructionToNodeTranslator();

		for (int pc = 0; pc < pcLabels.size(); pc++) {
			translator.translate(code.get(pc), pc, prototype.getLineAtPC(pc), pcLabels);
		}

//		System.out.println("[");
//		for (int i = 0; i < pcLabels.size(); i++) {
//			NLabel label = pcLabels.get(i);
//			System.out.println(i + ": " + label.toString());
//		}
//		System.out.println("]");

		Entry callEntry = new Entry("main", pcLabels.get(0));

		Set<Entry> entryPoints = new HashSet<>();
		entryPoints.add(callEntry);

		inlineInnerJumps(entryPoints);
		makeBlocks(entryPoints);

		applyTransformation(entryPoints, new CollectCPUAccounting());

		// remove repeated line info nodes
		applyTransformation(entryPoints, new RemoveRedundantLineNodes());

		// dissolve blocks
		dissolveBlocks(entryPoints);

		// remove all line info nodes
//		applyTransformation(entryPoints, new LinearSeqTransformation.Remove(Predicates.isClass(LineInfo.class)));

//		System.out.println();
//		printNodes(entryPoints);

		reachabilityGraph = reachabilityEdges(entryPoints);
		slots = dataFlow(callEntry);
	}

	private static class CollectCPUAccounting extends LinearSeqTransformation {

		@Override
		public void apply(LinearSeq seq) {
			List<AccountingNode> toBeRemoved = new ArrayList<>();

			int cost = 0;

			for (Linear n : seq.nodes()) {
				if (n instanceof AccountingNode) {
					AccountingNode an = (AccountingNode) n;
					if (n instanceof AccountingNode.Tick) {
						cost += 1;
						toBeRemoved.add(an);
					}
					else if (n instanceof AccountingNode.Sum) {
						cost += ((AccountingNode.Sum) n).cost;
						toBeRemoved.add(an);
					}
				}
			}

			for (AccountingNode an : toBeRemoved) {
				// remove all nodes
				an.remove();
			}

			if (cost > 0) {
				// insert cost node at the beginning
				seq.insertAtBeginning(new AccountingNode.Sum(cost));
			}
		}

	}

	private static class RemoveRedundantLineNodes extends LinearSeqTransformation {

		@Override
		public void apply(LinearSeq seq) {
			int line = -1;
			List<Linear> toBeRemoved = new ArrayList<>();

			for (Linear n : seq.nodes()) {
				if (n instanceof LineInfo) {
					LineInfo lineInfoNode = (LineInfo) n;
					if (lineInfoNode.line == line) {
						// no need to keep this one
						toBeRemoved.add(lineInfoNode);
					}
					line = lineInfoNode.line;
				}
			}

			for (Linear n : toBeRemoved) {
				n.remove();
			}

		}

	}

	private void applyTransformation(Iterable<Entry> entryPoints, LinearSeqTransformation tf) {
		for (Node n : reachableNodes(entryPoints)) {
			if (n instanceof LinearSeq) {
				LinearSeq seq = (LinearSeq) n;
				seq.apply(tf);
			}
		}
	}

	private void inlineInnerJumps(Iterable<Entry> entryPoints) {
		for (Node n : reachableNodes(entryPoints)) {
			if (n instanceof Target) {
				Target t = (Target) n;
				UnconditionalJump jmp = t.optIncomingJump();
				if (jmp != null) {
					Nodes.inline(jmp);
				}
			}
		}
	}

	private void makeBlocks(Iterable<Entry> entryPoints) {
		for (Node n : reachableNodes(entryPoints)) {
			if (n instanceof Target) {
				Target t = (Target) n;
				LinearSeq block = new LinearSeq();
				block.insertAfter(t);
				block.grow();
			}
		}
	}

	private void dissolveBlocks(Iterable<Entry> entryPoints) {
		applyTransformation(entryPoints, new LinearSeqTransformation() {
			@Override
			public void apply(LinearSeq seq) {
				seq.dissolve();
			}
		});
	}

	public static class Edges {
		// FIXME: may in principle be multisets
		public final Set<Node> in;
		public final Set<Node> out;

		public Edges() {
			this.in = new HashSet<>();
			this.out = new HashSet<>();
		}
	}

	public static class InOutSlots {
		public Slots in;
		public Slots out;

		public InOutSlots() {
			this.in = null;
			this.out = null;
		}
	}

	private Map<Node, InOutSlots> initSlots(Entry entryPoint) {
		Map<Node, InOutSlots> slots = new HashMap<>();
		for (Node n : reachableNodes(Collections.singleton(entryPoint))) {
			slots.put(n, new InOutSlots());
		}

		InOutSlots entryIos = slots.get(entryPoint);
		entryIos.out = entrySlots();

		return slots;
	}

	public Map<Node, InOutSlots> dataFlow(Entry entryPoint) {
		Map<Node, Edges> edges = reachabilityEdges(Collections.singleton(entryPoint));
		Map<Node, InOutSlots> slots = initSlots(entryPoint);

		Set<Node> nodes = edges.keySet();

		boolean changed;

		// FIXME: terribly inefficient

		do {
			changed = false;

			for (Node n : nodes) {

				Edges es = edges.get(n);
				Set<Node> in = es.in;

				InOutSlots ios = slots.get(n);

				if (!in.isEmpty()) {
					Slots newIn = null;
					for (Node inNode : in) {
						Slots t = slots.get(inNode).out;
						if (t != null) {
							newIn = newIn == null ? t : newIn.joinAll(t);
						}
					}

					Slots oldIn = ios.in;

					if (newIn != null && !newIn.equals(oldIn)) {
						ios.in = newIn;
						changed = true;

						// now recompute the output

						if (n instanceof SlotEffect) {
							SlotEffect eff = (SlotEffect) n;
							ios.out = eff.effect(newIn, prototype);
						}
						else {
							ios.out = newIn;
						}

						break;
					}

				}

			}

		} while (changed);

		return slots;
	}

	private Map<Node, Edges> reachabilityEdges(Iterable<Entry> entryPoints) {
		final Map<Node, Integer> timesVisited = new HashMap<>();
		final Map<Node, Edges> edges = new HashMap<>();

		NodeVisitor visitor = new NodeVisitor() {

			@Override
			public boolean visitNode(Node node) {
				if (timesVisited.containsKey(node)) {
					timesVisited.put(node, timesVisited.get(node) + 1);
					return false;
				}
				else {
					timesVisited.put(node, 1);
					if (!edges.containsKey(node)) {
						edges.put(node, new Edges());
					}
					return true;
				}
			}

			@Override
			public void visitEdge(Node from, Node to) {
				if (!edges.containsKey(from)) {
					edges.put(from, new Edges());
				}
				if (!edges.containsKey(to)) {
					edges.put(to, new Edges());
				}

				Edges fromEdges = edges.get(from);
				Edges toEdges = edges.get(to);

				fromEdges.out.add(to);
				toEdges.in.add(from);
			}
		};

		for (Entry entry : entryPoints) {
			entry.accept(visitor);
		}

		return Collections.unmodifiableMap(edges);
	}

	private void printNodes(Iterable<Entry> entryPoints) {
		ArrayList<Node> nodes = new ArrayList<>();
		Map<Node, Edges> edges = reachabilityEdges(entryPoints);

		for (Node n : edges.keySet()) {
			nodes.add(n);
		}

		System.out.println("[");
		for (int i = 0; i < nodes.size(); i++) {
			Node n = nodes.get(i);
			Edges e = edges.get(n);

			System.out.print("\t" + i + ": ");
			System.out.print("{ ");
			for (Node m : e.in) {
				int idx = nodes.indexOf(m);
				System.out.print(idx + " ");
			}
			System.out.print("} -> ");

			System.out.print(n.toString());

			System.out.print(" -> { ");
			for (Node m : e.out) {
				int idx = nodes.indexOf(m);
				System.out.print(idx + " ");
			}
			System.out.print("}");
			System.out.println();
		}
		System.out.println("]");
	}

	private Iterable<Node> reachableNodes(Iterable<Entry> entryPoints) {
		return reachability(entryPoints).keySet();
	}

	private Map<Node, Integer> reachability(Iterable<Entry> entryPoints) {
		final Map<Node, Integer> inDegree = new HashMap<>();

		NodeVisitor visitor = new NodeVisitor() {

			@Override
			public boolean visitNode(Node n) {
				if (inDegree.containsKey(n)) {
					inDegree.put(n, inDegree.get(n) + 1);
					return false;
				}
				else {
					inDegree.put(n, 1);
					return true;
				}
			}

			@Override
			public void visitEdge(Node from, Node to) {
				// no-op
			}

		};

		for (Entry entry : entryPoints) {
			entry.accept(visitor);
		}
		return Collections.unmodifiableMap(inDegree);
	}

	private Slots entrySlots() {
		Slots s = Slots.init(prototype.getMaximumStackSize());
		for (int i = 0; i < prototype.getNumberOfParameters(); i++) {
			s = s.updateType(i, Slots.SlotType.ANY);
		}
		return s;
	}

}