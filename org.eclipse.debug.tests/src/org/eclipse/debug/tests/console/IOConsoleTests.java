/*******************************************************************************
 * Copyright (c) 2019 Paul Pazderski and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Paul Pazderski - initial API and implementation
 *******************************************************************************/
package org.eclipse.debug.tests.console;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.tests.AbstractDebugTest;
import org.eclipse.debug.tests.TestUtil;
import org.eclipse.debug.tests.TestsPlugin;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleConstants;
import org.eclipse.ui.console.IConsoleDocumentPartitioner;
import org.eclipse.ui.console.IConsoleDocumentPartitionerExtension;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.IOConsole;
import org.eclipse.ui.console.IOConsoleOutputStream;

/**
 * Tests the {@link IOConsole}. Especially the partitioner and viewer parts.
 */
public class IOConsoleTests extends AbstractDebugTest {
	/**
	 * The console view used for the running test. Required to obtain access to
	 * consoles {@link StyledText} widget to simulate user input.
	 */
	@SuppressWarnings("restriction")
	private org.eclipse.ui.internal.console.ConsoleView consoleView;

	/** Track console finished property notification. */
	private final AtomicBoolean consoleFinished = new AtomicBoolean(false);

	/**
	 * Number of received log messages with severity error while running a
	 * single test method.
	 */
	private final AtomicInteger loggedErrors = new AtomicInteger();

	/** Listener to count error messages while testing. */
	private final ILogListener errorLogListener = (IStatus status, String plugin) -> {
		if (status.matches(IStatus.ERROR)) {
			loggedErrors.incrementAndGet();
		}
	};

	public IOConsoleTests() {
		super(IOConsoleTests.class.getSimpleName());
	}

	public IOConsoleTests(String name) {
		super(name);
	}

	@Override
	protected void setUp() throws Exception {
		super.setUp();

		// create or activate console view
		final IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		assertNotNull(window);
		final IWorkbenchPage activePage = window.getActivePage();
		assertNotNull(activePage);
		IViewPart viewPart = activePage.findView(IConsoleConstants.ID_CONSOLE_VIEW);
		if (viewPart == null) {
			viewPart = activePage.showView(IConsoleConstants.ID_CONSOLE_VIEW, null, IWorkbenchPage.VIEW_CREATE);
		}
		@SuppressWarnings("restriction")
		final org.eclipse.ui.internal.console.ConsoleView castConsoleView = (org.eclipse.ui.internal.console.ConsoleView) viewPart;
		consoleView = castConsoleView;
		activePage.activate(consoleView);

		// add error listener
		loggedErrors.set(0);
		Platform.addLogListener(errorLogListener);
	}

	@Override
	protected void tearDown() throws Exception {
		Platform.removeLogListener(errorLogListener);
		super.tearDown();

		assertEquals("Test triggered errors in IOConsole.", 0, loggedErrors.get());
	}

	/**
	 * Create test util connected to a new {@link IOConsole}.
	 *
	 * @param title console title
	 * @return util to help testing console functions
	 */
	protected IOConsoleTestUtil getTestUtil(String title) {
		final IOConsole console = new IOConsole(title, "", null, StandardCharsets.UTF_8.name(), true);
		consoleFinished.set(false);
		console.addPropertyChangeListener((PropertyChangeEvent event) -> {
			if (event.getSource() == console && IConsoleConstants.P_CONSOLE_OUTPUT_COMPLETE.equals(event.getProperty())) {
				consoleFinished.set(true);
			}
		});
		final IConsoleManager consoleManager = ConsolePlugin.getDefault().getConsoleManager();
		consoleManager.addConsoles(new IConsole[] { console });
		TestUtil.waitForJobs(getName(), 25, 10000);
		consoleManager.showConsoleView(console);
		@SuppressWarnings("restriction")
		final org.eclipse.ui.internal.console.IOConsolePage page = (org.eclipse.ui.internal.console.IOConsolePage) consoleView.getCurrentPage();
		final StyledText textPanel = (StyledText) page.getControl();
		return new IOConsoleTestUtil(console, textPanel, getName());
	}

	/**
	 * Close the console and optionally check content in {@link IOConsole}'s
	 * input stream.
	 * <p>
	 * Note: all output streams explicitly opened with
	 * {@link IOConsole#newOutputStream()} must be closed before.
	 * </p>
	 *
	 * @param c the test util containing the console to close
	 * @param expected content this {@link IOConsole} input stream has received
	 */
	protected void closeConsole(IOConsoleTestUtil c, String... expectedInputLines) throws IOException {
		if (consoleFinished.get()) {
			// This should only happen if no output streams where used and the
			// user input stream was explicit closed before
			TestUtil.log(IStatus.WARNING, TestsPlugin.PLUGIN_ID, "Console was finished before streams where explicit closed.");
		}

		c.closeOutputStream();
		if (c.getConsole().getInputStream() != null) {
			c.getConsole().getInputStream().close();
		}

		if (expectedInputLines.length > 0) {
			assertNotNull(c.getConsole().getInputStream());
			assertTrue("InputStream is empty.", c.getConsole().getInputStream().available() > 0);

			final List<String> inputLines = new ArrayList<>();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(c.getConsole().getInputStream(), c.getConsole().getCharset()))) {
				String line;
				while (reader.ready() && (line = reader.readLine()) != null) {
					inputLines.add(line);
				}
			}
			assertEquals("Input contains to many/few lines.", expectedInputLines.length, inputLines.size());
			for (int i = 0; i < expectedInputLines.length; i++) {
				assertEquals("Content of input line " + i + " not as expected.", expectedInputLines[i], inputLines.get(i));
			}
		}
		c.waitForScheduledJobs();
		assertTrue("Console close was not signaled.", consoleFinished.get());

		final IConsoleManager consoleManager = ConsolePlugin.getDefault().getConsoleManager();
		consoleManager.removeConsoles(new IConsole[] { c.getConsole() });
	}

	/**
	 * Test console clear.
	 */
	public void testConsoleClear() throws Exception {
		final IOConsoleTestUtil c = getTestUtil("Test clear");

		c.writeAndVerify("Hello World!");
		c.getDocument().replace(0, c.getContentLength(), "");
		c.waitForScheduledJobs();
		c.verifyContent("").verifyPartitions();

		c.writeAndVerify("New console content.");
		c.clear();
		assertEquals("Unexpected partition type.", IOConsoleTestUtil.inputPartitionType(), c.getPartitioner().getContentType(0));

		c.insertAndVerify("wrong").write("out").verifyContent("wrongout").verifyPartitions(2);
		c.clear().insertTypingAndVerify("i").write("ooo").verifyContent("iooo").verifyPartitions();
		c.enter().clear();

		c.insertAndVerify("gnorw").write("tuo").verifyContent("gnorwtuo").verifyPartitions(2);
		c.clear().insertTypingAndVerify("I").write("O").verifyContent("IO").verifyPartitions();
		c.insert("\r\n").clear();

		closeConsole(c, "i", "I");
	}

	/**
	 * Test multiple writes, i.e. {@link IOConsole} receives content from
	 * connected {@link IOConsoleOutputStream}.
	 */
	public void testWrites() throws Exception {
		final IOConsoleTestUtil c = getTestUtil("Test writes");
		final String[] strings = new String[] {
				"Hello ", "World", "foo-", "bar", "123 456" };
		for (String s : strings) {
			c.writeAndVerify(s);
		}
		c.write("\n");
		final String longString = String.join("", Collections.nCopies(1000, "0123456789"));
		c.writeAndVerify(longString);
		c.verifyContentByOffset(strings[1], strings[0].length());
		c.verifyContentByLine(String.join("", strings), 0);
		c.verifyPartitions();
		closeConsole(c);
	}

	/**
	 * Test {@link IOConsole} input stream, i.e. simulate user typing or pasting
	 * input in console.
	 */
	public void testUserInput() throws Exception {
		final IOConsoleTestUtil c = getTestUtil("Test input");
		final List<String> expectedInput = new ArrayList<>();

		c.insertAndVerify("RR").backspace(3).verifyContent("").verifyPartitions();
		c.insertAndVerify("remove").select(0, c.getContentLength()).verifyPartitions();
		c.insertTypingAndVerify("abc").insertAndVerify("123").verifyContent("abc123");
		c.moveCaret(-3).insertAndVerify("foo").insertTypingAndVerify("bar").verifyContentByOffset("123", c.getCaretOffset());
		c.moveCaretToLineEnd().backspace(2).verifyContent("abcfoobar1").verifyPartitions();
		c.insert("\r\n").backspace(5);
		expectedInput.add("abcfoobar1");
		int pos = c.getCaretOffset();
		c.insertTypingAndVerify("NewLine").moveCaret(-4).enter();
		expectedInput.add("NewLine");
		assertEquals("Expected newline entered inside line does not break this line.", c.getContentLength(), c.getCaretOffset());
		c.verifyPartitions().verifyContentByOffset("NewLine", pos);
		c.backspace().insertAndVerify("--").select(0, c.getContentLength()).insertTyping("<~>");
		c.verifyContentByLine("<~>", 2).verifyPartitions();
		c.select(-2, 1).insertAndVerify("-=-").verifyContentByLine("<-=->", 2).verifyPartitions();

		// multiline input
		c.clear().insertTyping("=").insert("foo\n><");
		expectedInput.add("=foo");
		c.moveCaretToEnd().moveCaret(-1);
		c.insert("abc\r\n123\n456");
		expectedInput.add(">abc<");
		expectedInput.add("123");
		c.enter().clear();
		expectedInput.add("456");

		closeConsole(c, expectedInput.toArray(new String[0]));
	}

	/**
	 * Test {@link IOConsole} with file as input source.
	 */
	public void testInputFile() throws Exception {
		final IOConsoleTestUtil c = getTestUtil("Test input file");
		// open default output stream to match usual behavior where two output
		// streams are open and to prevent premature console closing
		c.getDefaultOutputStream();
		try (InputStream in = new ByteArrayInputStream(new byte[0])) {
			c.getConsole().getInputStream().close();
			c.getConsole().setInputStream(in);
		}
		closeConsole(c);
		assertEquals("Test triggered errors in IOConsole.", 0, loggedErrors.get());
	}

	/**
	 * Test mixes of outputs and user inputs in various variants.
	 */
	public void testMixedWriteAndInput() throws Exception {
		final IOConsoleTestUtil c = getTestUtil("Test input output mix");
		final List<String> expectedInput = new ArrayList<>();

		// user input mixed with outputs without caret movement
		c.writeAndVerify("foo");
		c.insertTyping("~~~");
		c.writeAndVerify("bar");
		c.insertTyping("input.");
		c.verifyContent("foo~~~input.bar").verifyPartitions(3);
		c.enter().clear();
		expectedInput.add("~~~input.");

		// type in output or finished input partitions and replace input
		c.insert("fixed\n");
		expectedInput.add("fixed");
		c.setCaretOffset(2);
		c.insertTyping("+");
		c.writeAndVerify("out");
		c.moveCaretToEnd().moveCaret(-1);
		c.insert("more input");
		c.select(0, c.getContentLength()).backspace();
		c.insert("~~p#t").select(c.getContentLength() - 5, 2).insert("in");
		c.select(c.getContentLength() - 2, 1).insertTyping("u");
		c.enter().clear();
		expectedInput.add("input");

		// inserted input is shorter than existing input partition
		c.writeAndVerify("foo");
		c.insertTyping("><--input-partition--").setCaretOffset(4);
		c.writeAndVerify("bar");
		c.insertTypingAndVerify("short");
		c.verifyContent("foo>short<--input-partition--bar").verifyPartitions(3);
		c.enter().clear();
		expectedInput.add(">short<--input-partition--");

		// inserted input is longer than existing input partition
		c.writeAndVerify("Hello");
		c.insertTyping("><").moveCaret(-1);
		c.writeAndVerify("World");
		c.insertTypingAndVerify("user input");
		c.verifyContent("Hello>user input<World").verifyPartitions(3);
		c.enter().clear();
		expectedInput.add(">user input<");

		// replace and remove input
		c.writeAndVerify("oooo");
		c.insertTyping("input");
		c.writeAndVerify("output");
		c.verifyContent("ooooinputoutput").verifyPartitions(3);
		c.select(4, 5).insertAndVerify("iiii");
		c.verifyContent("ooooiiiioutput").verifyPartitions(3);
		c.select(4, 4).backspace();
		c.verifyContent("oooooutput").verifyPartitions(2);
		c.enter().clear();
		expectedInput.add("");

		// insert alternating into distinct input partitions
		c.insertTypingAndVerify("ac").writeAndVerify("ooo");
		c.setCaretOffset(1).insertTypingAndVerify("b");
		c.moveCaretToEnd().insertTypingAndVerify("123");
		c.verifyContent("abcooo123").verifyPartitions(3);
		c.enter().clear();
		expectedInput.add("abc123");

		// insert alternating into distinct input partitions
		c.insertTypingAndVerify("abc").writeAndVerify("ooo");
		c.moveCaretToEnd().insertTypingAndVerify("13").writeAndVerify("OOO");
		c.moveCaret(-1).insertTyping("2");
		c.moveCaretToStart().insertTyping("ABC");
		c.verifyContent("ABCabcooo123OOO").verifyPartitions(4);
		c.enter().clear();
		expectedInput.add("ABCabc123");

		// insert at partition borders
		c.writeAndVerify("###").insertTyping("def").writeAndVerify("###");
		c.setCaretOffset(6).insertAndVerify("ghi");
		c.setCaretOffset(3).insertTypingAndVerify("abc");
		c.moveCaretToLineStart().insertTyping(":");
		c.enter().clear();
		expectedInput.add(":abcdefghi");

		// try to overwrite read-only content
		c.writeAndVerify("o\u00F6O").insertTyping("\u00EFiI").writeAndVerify("\u00D6\u00D8\u00F8");
		// line content: oöOiïIÖØø
		c.verifyContent("o\u00F6O" + "\u00EFiI" + "\u00D6\u00D8\u00F8").verifyPartitions(2);
		c.select(4, 4).backspace();
		c.verifyContent("o\u00F6O" + "\u00EF" + "\u00D6\u00D8\u00F8").verifyPartitions(2);
		c.enter().clear();
		expectedInput.add("\u00EF");

		closeConsole(c, expectedInput.toArray(new String[0]));
	}

	/**
	 * Test enabling/disabling control character interpretation.
	 */
	public void testControlCharacterSettings() throws Exception {
		final IOConsoleTestUtil c = getTestUtil("Test options");

		c.getConsole().setHandleControlCharacters(false);
		c.getConsole().setCarriageReturnAsControlCharacter(false);
		c.write("\r..");
		assertEquals("Wrong number of lines.", 2, c.getDocument().getNumberOfLines());

		c.getConsole().setCarriageReturnAsControlCharacter(true);
		c.write("\r..");
		assertEquals("Wrong number of lines.", 3, c.getDocument().getNumberOfLines());

		c.getConsole().setHandleControlCharacters(true);
		c.getConsole().setCarriageReturnAsControlCharacter(false);
		c.write("\r..");
		assertEquals("Wrong number of lines.", 4, c.getDocument().getNumberOfLines());

		c.getConsole().setCarriageReturnAsControlCharacter(true);
		c.write("\r..");
		assertEquals("Wrong number of lines.", 4, c.getDocument().getNumberOfLines());

		closeConsole(c);
		assertEquals("Test triggered errors in IOConsole.", 0, loggedErrors.get());
	}

	/**
	 * Test handling of <code>\b</code>.
	 */
	public void testBackspaceControlCharacter() throws Exception {
		final IOConsoleTestUtil c = getTestUtil("Test \\b");
		c.getConsole().setCarriageReturnAsControlCharacter(false);
		c.getConsole().setHandleControlCharacters(true);
		try (IOConsoleOutputStream err = c.getConsole().newOutputStream()) {
			// test simple backspace cases
			c.write("\b").write("|").verifyContent("|").verifyPartitions();
			c.writeFast("\b").write("/").verifyContent("/").verifyPartitions();
			c.writeFast("\b\b\b").write("-\b").verifyContent("-").verifyPartitions();
			c.writeFast("\b1\b2\b3\b").write("\\").verifyContent("\\").verifyPartitions();

			// test existing output is overwritten independent from stream
			c.clear();
			c.writeFast("out").write("err", err).verifyContent("outerr").verifyPartitions(2);
			c.writeFast("\b\b\b\b\b\b\b\b\b\b\b\b\b");
			c.writeFast("err", err).write("out").verifyContent("errout").verifyPartitions(2);
			c.writeFast("\b\b\b\b\b\b\b\b\b\b\b\b\b");
			c.writeFast("12", err).writeFast("345").write("6789", err).verifyContent("123456789").verifyPartitions(3);

			// test backspace stops at line start
			c.clear();
			c.writeFast("First line\n").writeFast("\b\b", err).writeFast("Zecond line");
			c.writeFast("\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b\b");
			c.write("S", err).verifyContentByLine("First line", 0).verifyContentByLine("Second line", 1).verifyPartitions(2);

			// test in combination with input partitions
			c.clear();
			c.writeAndVerify("out").insertTyping("input").writeAndVerify("err", err).verifyContent("outinputerr").verifyPartitions(3);
			c.setCaretOffset(6).backspace().backspace().writeAndVerify("~~~").verifyContentByOffset("~~~", -3).verifyPartitions(3);
			c.verifyContent("outiuterr~~~");
			c.writeFast("\b\b\b\b\b\b\b\b\b\b\b\b\b");
			c.write("output").verifyContent("outiutput~~~").verifyPartitions(3);
			c.setCaretOffset(4).insertTyping("np").verifyContent("outinputput~~~").verifyPartitions(3);
			c.write("+++++", err).verifyContent("outinputput+++++").verifyPartitions(3);
			c.writeFast(String.join("", Collections.nCopies(11, "\b")));
			c.write("err", err).verifyContent("errinputput+++++").verifyPartitions(3);

			c.clear();
			c.writeAndVerify("ooooo").insertTyping("iii").write("eeee", err).moveCaretToEnd().insertTyping("i").write("oo");
			c.verifyContent("oooooiiieeeeioo").verifyPartitions(3);
			c.writeFast(String.join("", Collections.nCopies(7, "\b")));
			c.write("xx").verifyContent("ooooxiiixeeeioo").verifyPartitions(3);

			c.clear();
			c.insert("iiii").writeFast("\b").write("o").verifyContent("iiiio").verifyPartitions(2);
			c.write("\b\bee", err).verifyContentByOffset("iiiiee", 0).verifyPartitions(2);
			c.writeFast("\b\b\b\b\b\b\b\b", err).write("o").verifyContent("iiiioe").verifyPartitions(3);

			// test if backspace overruns line breaks introduced by input
			// (at the moment it should overrun those line breaks)
			c.clear();
			c.writeAndVerify("1", err).insertTyping("input").enter().write("2");
			c.verifyContentByLine("1input", 0).verifyContentByLine("2", 1).verifyPartitions(3);
			c.writeFast("\b\b\b\b\b\b\b\b\b\b\b\b\b", err);
			c.write("???").verifyContentByLine("?input", 0).verifyContentByLine("??", 1).verifyPartitions(3);
			c.writeFast("\b\b").writeFast("\b", err).write("><~");
			c.verifyContentByLine(">input", 0).verifyContentByLine("<~", 1).verifyPartitions(3);

			// test output cursor moves according to changed input
			c.clear();
			c.writeAndVerify("abc", err).insert("<>").write("def").verifyContent("abc<>def").verifyPartitions(3);
			c.write("\b\b").setCaretOffset(4).insertTypingAndVerify("-=-").verifyContent("abc<-=->def").verifyPartitions(3);
			c.moveCaret(-1).backspace().verifyContent("abc<-->def").verifyPartitions(3);
			c.write("e\b\b\b\b", err).insertTyping("++").verifyContent("abc<-++->def").verifyPartitions(3);
			c.select(0, c.getDocument().getLength()).backspace().write("b").verifyContent("abcdef").verifyPartitions(3);

			// break output line
			// NOTE: this may not be the desired behavior
			c.clear();
			c.writeFast("1.2.").writeFast("\b\b").write("\n");
			c.verifyContentByLine("1.", 0).verifyContentByLine(".", 1).verifyPartitions();
			c.writeFast("\b\b\b\b").write("2.");
			c.verifyContentByLine("1.", 0).verifyContentByLine("2.", 1).verifyPartitions();
		}
		closeConsole(c);
		assertEquals("Test triggered errors in IOConsole.", 0, loggedErrors.get());
	}

	/**
	 * Test handling of <code>\r</code>.
	 */
	public void testCarriageReturnControlCharacter() throws Exception {
		final IOConsoleTestUtil c = getTestUtil("Test \\r");
		c.getConsole().setCarriageReturnAsControlCharacter(true);
		c.getConsole().setHandleControlCharacters(true);
		try (IOConsoleOutputStream err = c.getConsole().newOutputStream()) {
			// test simple carriage return cases
			c.write("\r");
			assertEquals("Wrong number of lines.", 1, c.getDocument().getNumberOfLines());
			c.writeFast("bad", err).write("\rgood").verifyContent("good").verifyPartitions(1);
			assertEquals("Wrong number of lines.", 1, c.getDocument().getNumberOfLines());

			// test carriage return stops at line start
			c.clear();
			c.writeFast("First line\r\n").write("Zecond line", err);
			c.verifyContentByLine("First line", 0).verifyContentByLine("Zecond line", 1).verifyPartitions(2);
			assertEquals("Wrong number of lines.", 2, c.getDocument().getNumberOfLines());
			c.writeFast("\r").write("3.    ").verifyContentByLine("3.     line", 1).verifyPartitions(2);
			assertEquals("Wrong number of lines.", 2, c.getDocument().getNumberOfLines());
			c.writeFast("\r\r\r", err).write("Second").verifyContentByLine("Second line", 1).verifyPartitions(2);
			assertEquals("Wrong number of lines.", 2, c.getDocument().getNumberOfLines());

			// test carriage return with input partitions
			c.clear();
			c.insertTypingAndVerify("input").writeFast("out\r").write("err", err);
			c.verifyContent("inputerr").verifyPartitions(2);
			c.enter().write("\rout").verifyContentByLine("inputout", 0).verifyPartitions(2);
			c.write("err", err).verifyContentByLine("err", 1).verifyPartitions(3);
			c.write("\roooooo").verifyContentByLine("inputooo", 0).verifyContentByLine("ooo", 1).verifyPartitions(2);

			// test in combination with \r\n
			c.clear();
			c.write("\r\n");
			assertEquals("Wrong number of lines.", 2, c.getDocument().getNumberOfLines());
			c.writeFast("err", err).writeFast("\r\r\r\r\r\r\r\r\n\n").write("out");
			assertEquals("Wrong number of lines.", 4, c.getDocument().getNumberOfLines());
			c.verifyContentByLine("out", -1).verifyPartitions();
			assertTrue("Line breaks did not overwrite text.", !c.getDocument().get().contains("err"));
		}
		closeConsole(c);
		assertEquals("Test triggered errors in IOConsole.", 0, loggedErrors.get());
	}

	/**
	 * Test larger number of partitions with pseudo random console content.
	 */
	public void testManyPartitions() throws IOException {
		final IOConsoleTestUtil c = getTestUtil("Test many partitions");
		final List<String> expectedInput = new ArrayList<>();
		final StringBuilder input = new StringBuilder();
		try (IOConsoleOutputStream otherOut = c.getConsole().newOutputStream()) {
			final Random rand = new Random(12);
			final StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 20; i++) {
				for (int j = 0; j < 80; j += sb.length()) {
					sb.setLength(0);
					for (int k = rand.nextInt(15) + 1; k > 0; k--) {
						// add printable ASCII character
						sb.append((char) (rand.nextInt(95) + 32));
					}
					switch (rand.nextInt(5)) {
						case 0:
							c.insert(sb.toString());
							input.append(sb);
							break;

						case 1:
						case 2:
							c.writeFast(sb.toString(), otherOut);
							break;

						default:
							c.writeFast(sb.toString());
							break;
					}
				}
				c.enter().verifyPartitions();
				expectedInput.add(input.toString());
				input.setLength(0);
			}
		}
		closeConsole(c, expectedInput.toArray(new String[0]));
	}

	/**
	 * Test console trimming.
	 */
	public void testTrim() throws Exception {
		final IOConsoleTestUtil c = getTestUtil("Test trim");
		try (IOConsoleOutputStream otherOut = c.getConsole().newOutputStream()) {
			c.writeFast("first\n");
			for (int i = 0; i < 20; i++) {
				c.writeFast("0123456789\n", (i & 1) == 0 ? c.getDefaultOutputStream() : otherOut);
			}
			c.write("last\n");
			c.verifyContentByLine("first", 0).verifyContentByLine("last", -2);
			assertTrue("Document not filled.", c.getDocument().getNumberOfLines() > 15);

			c.getConsole().setWaterMarks(50, 100);
			c.waitForScheduledJobs();
			c.verifyContentByOffset("0123456789", 0);
			assertTrue("Document not trimmed.", c.getDocument().getNumberOfLines() < 15);
		}
		closeConsole(c);
	}

	/**
	 * Some extra tests for IOConsolePartitioner.
	 */
	public void testIOPartitioner() throws Exception {
		final IOConsoleTestUtil c = getTestUtil("Test partitioner");

		c.writeAndVerify("output");
		c.getDocument().replace(2, 1, ":::");
		c.verifyPartitions();

		c.clear().insertAndVerify("input").enter();
		c.getDocument().replace(3, 0, "()");
		c.verifyInputPartitions(0, c.getContentLength());

		c.clear().writeAndVerify("><");
		c.getDocument().replace(1, 0, "a\nb\r\nc");
		c.verifyContent(">a\nb\r\nc<").verifyPartitions();

		c.clear().writeAndVerify(")");
		c.getDocument().replace(0, 0, "(");
		c.verifyContent("()").verifyPartitions();

		closeConsole(c);
		assertEquals("Test triggered errors in IOConsole.", 0, loggedErrors.get());
	}

	/**
	 * Tests for the {@link IConsoleDocumentPartitioner} interface.
	 */
	public void testIConsoleDocumentPartitioner() throws Exception {
		final IOConsoleTestUtil c = getTestUtil("Test IConsoleDocumentPartitioner");
		try (IOConsoleOutputStream otherOut = c.getConsole().newOutputStream()) {
			StyleRange[] styles = c.getPartitioner().getStyleRanges(0, 1);
			assertEquals("Got fake styles.", 0, (styles == null ? 0 : styles.length));

			c.insertAndVerify("#\n");
			c.insertTyping("L");
			c.writeFast("orem ipsum dolor sit amet, consetetur sadipscing elitr,\n");
			c.writeFast("sed diam nonumy eirmod tempor invidunt ut labore et dolore\n", otherOut);
			c.writeFast("magna aliquyam erat, sed diam voluptua. At vero eos et accusam\n");
			c.writeFast("et justo duo dolores et ea rebum. Stet clita kasd gubergren,\n", otherOut);
			c.write("no sea takimata sanctus est Lorem ipsum dolor sit amet.\n");
			final int loremEnd = c.getContentLength();
			c.moveCaretToEnd().insertTypingAndVerify("--").writeAndVerify("ooo");
			c.setCaretLineRelative(1).insertTypingAndVerify("-");
			c.verifyContentByLine("---ooo", -1);


			styles = c.getPartitioner().getStyleRanges(0, c.getContentLength());
			checkOverlapping(styles);
			assertNotNull("Partitioner provided no styles.", styles);
			assertTrue("Expected more styles.", styles.length >= 3);

			styles = c.getPartitioner().getStyleRanges(5, 20);
			checkOverlapping(styles);
			assertNotNull("Partitioner provided no styles.", styles);
			assertEquals("Number of styles:", 1, styles.length);

			styles = c.getPartitioner().getStyleRanges(loremEnd + 1, 1);
			checkOverlapping(styles);
			assertNotNull("Partitioner provided no styles.", styles);
			assertEquals("Number of styles:", 1, styles.length);

			styles = c.getPartitioner().getStyleRanges(loremEnd, c.getContentLength() - loremEnd);
			checkOverlapping(styles);
			assertNotNull("Partitioner provided no styles.", styles);
			assertEquals("Number of styles:", 2, styles.length);

			styles = c.getPartitioner().getStyleRanges(loremEnd - 3, 5);
			checkOverlapping(styles);
			assertNotNull("Partitioner provided no styles.", styles);
			assertEquals("Number of styles:", 2, styles.length);

			styles = c.getPartitioner().getStyleRanges(loremEnd - 3, 8);
			checkOverlapping(styles);
			assertNotNull("Partitioner provided no styles.", styles);
			assertEquals("Number of styles:", 3, styles.length);


			assertTrue("Offset should be read-only.", c.getPartitioner().isReadOnly(0));
			assertTrue("Offset should be read-only.", c.getPartitioner().isReadOnly(1));
			assertFalse("Offset should be writable.", c.getPartitioner().isReadOnly(2));
			for (int i = 3; i < loremEnd; i++) {
				assertTrue("Offset should be read-only.", c.getPartitioner().isReadOnly(i));
			}
			assertFalse("Offset should be writable.", c.getPartitioner().isReadOnly(loremEnd + 0));
			assertFalse("Offset should be writable.", c.getPartitioner().isReadOnly(loremEnd + 1));
			assertFalse("Offset should be writable.", c.getPartitioner().isReadOnly(loremEnd + 2));
			assertTrue("Offset should be read-only.", c.getPartitioner().isReadOnly(loremEnd + 3));
			assertTrue("Offset should be read-only.", c.getPartitioner().isReadOnly(loremEnd + 4));
			assertTrue("Offset should be read-only.", c.getPartitioner().isReadOnly(loremEnd + 5));

			if (c.getPartitioner() instanceof IConsoleDocumentPartitionerExtension) {
				final IConsoleDocumentPartitionerExtension extension = (IConsoleDocumentPartitionerExtension) c.getPartitioner();
				assertFalse("Writable parts not recognized.", extension.isReadOnly(0, c.getContentLength()));
				assertTrue("Read-only parts not recognized.", extension.containsReadOnly(0, c.getContentLength()));
				assertFalse("Writable parts not recognized.", extension.isReadOnly(0, 3));
				assertTrue("Read-only parts not recognized.", extension.containsReadOnly(0, 3));
				assertFalse("Area should be writable.", extension.isReadOnly(loremEnd, 3));
				assertFalse("Area should be writable.", extension.containsReadOnly(loremEnd, 3));
				assertTrue("Area should be read-only.", extension.isReadOnly(6, 105));
				assertTrue("Area should be read-only.", extension.containsReadOnly(8, 111));

				assertTrue("Read-only parts not found.", extension.computeReadOnlyPartitions().length > 0);
				assertTrue("Writable parts not found.", extension.computeWritablePartitions().length > 0);
				assertTrue("Read-only parts not found.", extension.computeReadOnlyPartitions(loremEnd - 5, 7).length > 0);
				assertTrue("Writable parts not found.", extension.computeWritablePartitions(loremEnd - 5, 7).length > 0);
				assertTrue("Area should be read-only.", extension.computeReadOnlyPartitions(5, 100).length > 0);
				assertEquals("Area should be read-only.", 0, extension.computeWritablePartitions(5, 100).length);
				assertEquals("Area should be writable.", 0, extension.computeReadOnlyPartitions(loremEnd, 2).length);
				assertTrue("Area should be writable.", extension.computeWritablePartitions(loremEnd, 2).length > 0);

				assertEquals("Got wrong offset.", 0, extension.getNextOffsetByState(0, false));
				assertEquals("Got wrong offset.", 2, extension.getNextOffsetByState(0, true));
				assertEquals("Got wrong offset.", 0, extension.getPreviousOffsetByState(0, false));
				assertEquals("Got wrong offset.", -1, extension.getPreviousOffsetByState(0, true));
				assertEquals("Got wrong offset.", 1, extension.getNextOffsetByState(1, false));
				assertEquals("Got wrong offset.", 2, extension.getNextOffsetByState(1, true));
				assertEquals("Got wrong offset.", 1, extension.getPreviousOffsetByState(1, false));
				assertEquals("Got wrong offset.", -1, extension.getPreviousOffsetByState(1, true));
				assertEquals("Got wrong offset.", 3, extension.getNextOffsetByState(2, false));
				assertEquals("Got wrong offset.", 2, extension.getNextOffsetByState(2, true));
				assertEquals("Got wrong offset.", 1, extension.getPreviousOffsetByState(2, false));
				assertEquals("Got wrong offset.", 2, extension.getPreviousOffsetByState(2, true));
				for (int i = 3; i < loremEnd; i++) {
					assertEquals("Got wrong offset.", i, extension.getNextOffsetByState(i, false));
					assertEquals("Got wrong offset.", loremEnd, extension.getNextOffsetByState(i, true));
					assertEquals("Got wrong offset.", i, extension.getPreviousOffsetByState(i, false));
					assertEquals("Got wrong offset.", 2, extension.getPreviousOffsetByState(i, true));
				}
				assertEquals("Got wrong offset.", loremEnd + 3, extension.getNextOffsetByState(loremEnd, false));
				assertEquals("Got wrong offset.", loremEnd, extension.getNextOffsetByState(loremEnd, true));
				assertEquals("Got wrong offset.", loremEnd - 1, extension.getPreviousOffsetByState(loremEnd, false));
				assertEquals("Got wrong offset.", loremEnd, extension.getPreviousOffsetByState(loremEnd, true));
				assertEquals("Got wrong offset.", loremEnd + 3, extension.getNextOffsetByState(loremEnd + 2, false));
				assertEquals("Got wrong offset.", loremEnd + 2, extension.getNextOffsetByState(loremEnd + 2, true));
				assertEquals("Got wrong offset.", loremEnd - 1, extension.getPreviousOffsetByState(loremEnd + 2, false));
				assertEquals("Got wrong offset.", loremEnd + 2, extension.getPreviousOffsetByState(loremEnd + 2, true));
			} else {
				TestUtil.log(IStatus.INFO, TestsPlugin.PLUGIN_ID, "IOConsole partitioner does not implement " + IConsoleDocumentPartitionerExtension.class.getName() + ". Skip those tests.");
			}
		}
		c.verifyPartitions();
		closeConsole(c, "#");
		assertEquals("Test triggered errors in IOConsole.", 0, loggedErrors.get());
	}

	/**
	 * Check if there is any offset which received two styles.
	 *
	 * @param styles the styles to check
	 */
	private void checkOverlapping(StyleRange[] styles) {
		if (styles == null || styles.length <= 1) {
			return;
		}
		Arrays.sort(styles, (a, b) -> Integer.compare(a.start, b.start));
		int lastEnd = Integer.MIN_VALUE;
		for (StyleRange s : styles) {
			assertTrue("Styles overlap.", lastEnd <= s.start);
			lastEnd = s.start + s.length;
		}
	}
}