# dfalex

Scanning / Lexical Analysis Without All The Fuss
================================================

Sometimes you need faster and more robust matching than you can get out of Java regular expressions.  Maybe they're too slow for you, or you get stack overflows when you match things that are too long, or maybe you want to search for many patterns simultaneously.  There plenty of lexical analysis tools you can use, but they invovle a lot of fuss.  They make you write specifications in a domain-specific language, often mixed with code, and then generate new java code for a scanner that you have to incorporate into your build and use in pretty specific ways.

DFALex provides that powerful matching capability without all the fuss.  It will build you a deterministic finite automaton (DFA, googlable) for a matching/finding multiple patterns in strings simultaneously, which you can then use with various matcher classes to perform searching or scanning operations.

Unlike other tools which use DFAs internally, but only build scanners with them, DFALex provides you with the actual DFA in an easy-to-use form.  Yes, you can use it in standard scanners, but you can also use it in other ways that don't fit that mold.

Start Here:
-----------

* **DfaBuilder** for building DFAs

* **Pattern** and **CharRange** for specifying patterns to match

* **StringMatcher** for using your DFAs to find patterns in strings

Requirements
------------

DFAlex needs Java 8 or better.  No special libraries are required. Just grab the source in src/ or a jar from bin/.  There won't be any official releases until more tests and examples are added.

If you want to run the tests, you'll need JUnit4.

About
-----

DFALex is written by Matt Timmermans, and is all new code.
DFAs are generated from NFAs with a starndard powerset construction, and minimized used a very fast hash-based variant of Hopcroft's algorithm.

