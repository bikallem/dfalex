/*
 * Copyright 2015 Matthew Timmermans
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.nobigsoftware.dfalex;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.nobigsoftware.util.BuilderCache;
import com.nobigsoftware.util.SHAOutputStream;

/**
 * Builds deterministic finite automata (google phrase) or DFAs that find patterns in strings
 * <P>
 * Given a set of patterns and the desired result of matching each pattern, you can produce a
 * DFA that will simultaneously match a sequence of characters against all of those patterns.
 * <P>
 * You can also build DFAs for multiple sets of patterns simultaneously. The resulting DFAs will
 * be optimized to share states wherever possible.
 * <P>
 * When you build a DFA to match a set of patterns, you get a "start state" (a {@link DfaState}) for
 * that pattern set. Each character of a string can be passed in turn to {@link DfaState#getNextState(char)},
 * which will return a new {@link DfaState}.
 * <P>
 * {@link DfaState#getMatch()} can be called at any time to get the MATCHRESULT (if any) for
 * the patterns that match the characters processed so far.
 * <P>
 * A {@link DfaState} can be used with a {@link StringMatcher} to find instances of patterns in strings,
 * or with other pattern-matching classes.
 * <P>
 * NOTE that building a Dfa is a complex procedure.  You should typically do it only once for each
 * pattern set you want to use.  Usually you would do this in a static initializer.
 * <P>
 * You can provide a cache that can remember and recall built DFAs, which allows you to build DFAs
 * during your build process in various ways, instead of building them at runtime.  Or you can use
 * the cache to store built DFAs on the first run of your program so they don't need to be built
 * the next time...  But this is usually unnecessary, since building DFAs is more than fast enough to
 * do during runtime initialization.
 * 
 * @param MATCHRESULT The type of result to produce by matching a pattern.  This must be serializable
 *      to support caching of built DFAs
 */
public class DfaBuilder<MATCHRESULT extends Serializable>
{
    //dfa types for cache keys
    private static final int DFATYPE_MATCHER = 0;
    private static final int DFATYPE_REVERSEFINDER = 1;
    
    private final BuilderCache m_cache;
	private final Map<MATCHRESULT, List<Matchable>> m_patterns = new LinkedHashMap<>();
	
	/**
	 * Create a new DfaBuilder without a {@link BuilderCache}
	 */
	public DfaBuilder()
	{
	    m_cache = null;
	}
	
	/**
	 * Create a new DfaBuilder, with a builder cache to bypass recalculation of pre-built DFAs
	 * 
	 * @param cache    The BuilderCache to use
	 */
	public DfaBuilder(BuilderCache cache)
	{
	    m_cache = cache;
	}
	
	/**
	 * Reset this DFA builder by forgetting all the patterns that have been added
	 */
	public void clear()
	{
	    m_patterns.clear();
	}
	
	public void addPattern(Matchable pat, MATCHRESULT accept)
	{
		List<Matchable> patlist = m_patterns.computeIfAbsent(accept, x -> new ArrayList<>());
		patlist.add(pat);
	}
	
	
    /**
     * Build DFA for a single language
     * <P>
     * The resulting DFA matches ALL patterns that have been added to this builder
     * 
     * @param ambiguityResolver     When patterns for multiple results match the same string, this is called to
     *                              combine the multiple results into one.  If this is null, then a DfaAmbiguityException
     *                              will be thrown in that case.
     *  @return The start state for a DFA that matches the set of patterns in language
     */
    public DfaState<MATCHRESULT> build(DfaAmbiguityResolver<? super MATCHRESULT> ambiguityResolver)
    {
        return build(Collections.singletonList(m_patterns.keySet()), ambiguityResolver).get(0);
    }

    /**
     * Build DFA for a single language
     * <P>
     * The language is specified as a subset of available MATCHRESULTs, and will include patterns
     * for each result in its set.
     * 
     * @param language     set defining the languages to build
     * @param ambiguityResolver     When patterns for multiple results match the same string, this is called to
     *                              combine the multiple results into one.  If this is null, then a DfaAmbiguityException
     *                              will be thrown in that case.
     *  @return The start state for a DFA that matches the set of patterns in language
     */
    public DfaState<MATCHRESULT> build(Set<MATCHRESULT> language, DfaAmbiguityResolver<? super MATCHRESULT> ambiguityResolver)
    {
        return build(Collections.singletonList(language), ambiguityResolver).get(0);
    }

    /**
	 * Build DFAs for multiple languages simultaneously.
	 * <P>
	 * Each language is specified as a subset of available MATCHRESULTs, and will include patterns
	 * for each result in its set.
	 * <P>
	 * Languages built simultaneously will be globally minimized and will share as many states as possible.
	 * 
	 * @param languages 	sets defining the languages to build
	 * @param ambiguityResolver	 	When patterns for multiple results match the same string, this is called to
	 * 								combine the multiple results into one.	If this is null, then a DfaAmbiguityException
	 * 								will be thrown in that case.
	 * @return Start states for DFAs that match the given languages.  This will have the same length as languages, with
	 *         corresponding start states in corresponding positions.
	 */
    @SuppressWarnings("unchecked")
    public List<DfaState<MATCHRESULT>> build(List<Set<MATCHRESULT>> languages, DfaAmbiguityResolver<? super MATCHRESULT> ambiguityResolver)
    {
        if (languages.isEmpty())
        {
            return Collections.emptyList();
        }
        
        SerializableDfa<MATCHRESULT> serializableDfa = null;
        if (m_cache == null)
        {
            serializableDfa = _build(languages, ambiguityResolver);
        }
        else
        {
            String cacheKey = _getCacheKey(DFATYPE_MATCHER, languages, ambiguityResolver);
            serializableDfa = (SerializableDfa<MATCHRESULT>) m_cache.getCachedItem(cacheKey);
            if (serializableDfa == null)
            {
                serializableDfa = _build(languages, ambiguityResolver);
                m_cache.maybeCacheItem(cacheKey, serializableDfa);
            }
        }
        return serializableDfa.getStartStates();
    }
    
    /**
     * Build the reverse finder DFA for all patterns that have been added to this builder
     * <P>
     * The "reverse finder DFA" for a set of patterns is applied to a string backwards from the end, and will
     * produce a {@link Boolean#TRUE} result at every position where a non-empty string match for one of the
     * patterns starts. At other positions it will produce null result. 
     * <P>
     * For searching through an entire string, using a reverse finder with {@link StringSearcher} is faster than matching
     * with just the DFA for the language, especially for strings that have no matches.
     * 
     * @return The start state for the reverse finder DFA
     */
    public DfaState<Boolean> buildReverseFinder()
    {
        return buildReverseFinders(Collections.singletonList(m_patterns.keySet())).get(0);
    }

    /**
     * Build the reverse finder DFA for a language
     * <P>
     * The language is specified as a subset of available MATCHRESULTs, and will include patterns
     * for each result in its set.
     * <P>
     * The "reverse finder DFA" for a language is applied to a string backwards from the end, and will
     * produce a {@link Boolean#TRUE} result at every position where a non-empty string in the language starts. At
     * other positions it will produce null result. 
     * <P>
     * For searching through an entire string, using a reverse finder with {@link StringSearcher} is faster than matching
     * with just the DFA for the language, especially for strings that have no matches.
     * 
     * @param language     set defining the languages to build
     * @return The start state for the reverse finder DFA
     */
    public DfaState<Boolean> buildReverseFinder(Set<MATCHRESULT> language)
    {
        return buildReverseFinders(Collections.singletonList(language)).get(0);
    }

    /**
     * Build reverse finder DFAs for multiple languages simultaneously.
     * <P>
     * Each language is specified as a subset of available MATCHRESULTs, and will include patterns
     * for each result in its set.
     * <P>
     * The "reverse finder DFA" for a language is applied to a string backwards from the end, and will
     * produce a {@link Boolean#TRUE} result at every position where a non-empty string in the language starts. At
     * other positions it will produce null result. 
     * <P>
     * For searching through an entire string, using a reverse finder with {@link StringSearcher} is faster than matching
     * with just the DFA for the language, especially for strings that have no matches.
     * 
     * @param languages     sets defining the languages to build
     * @return Start states for reverse finders for the given languages.  This will have the same length as languages, with
     *         corresponding start states in corresponding positions.
     */
    @SuppressWarnings("unchecked")
    public List<DfaState<Boolean>> buildReverseFinders(List<Set<MATCHRESULT>> languages)
    {
        if (languages.isEmpty())
        {
            return Collections.emptyList();
        }
        
        SerializableDfa<Boolean> serializableDfa = null;
        if (m_cache == null)
        {
            serializableDfa = _buildReverseFinders(languages);
        }
        else
        {
            String cacheKey = _getCacheKey(DFATYPE_REVERSEFINDER, languages, null);
            serializableDfa = (SerializableDfa<Boolean>) m_cache.getCachedItem(cacheKey);
            if (serializableDfa == null)
            {
                serializableDfa = _buildReverseFinders(languages);
                m_cache.maybeCacheItem(cacheKey, serializableDfa);
            }
        }
        return serializableDfa.getStartStates();
    }
    
    /**
     * Build a {@link StringSearcher} for all the patterns that have been added to this builder
     * 
     * @param ambiguityResolver     When patterns for multiple results match the same string, this is called to
     *                              combine the multiple results into one.  If this is null, then a DfaAmbiguityException
     *                              will be thrown in that case.
     *  @return A {@link StringSearcher} for all the patterns in this builder
     */
    public StringSearcher<MATCHRESULT> buildStringSearcher(DfaAmbiguityResolver<MATCHRESULT> ambiguityResolver)
    {
        return new StringSearcher<>(build(ambiguityResolver), buildReverseFinder());
    }
    
    /**
     * Build DFAs from a provided NFA
     * <P>
     * This method is used when you want to build the NFA yourself instead of letting
     * this class do it.
     * <P>
     * Languages built simultaneously will be globally minimized and will share as many states as possible.
     * 
     * @param nfa           The NFA
     * @param nfaStartStates     The return value will include the DFA states corresponding to these NFA states, in the same order
     * @param ambiguityResolver     When patterns for multiple results match the same string, this is called to
     *                              combine the multiple results into one.  If this is null, then a DfaAmbiguityException
     *                              will be thrown in that case.
     * @param cache If this cache is non-null, it will be checked for a memoized result for this NFA, and will be populated
     *      with a memoized result when the call is complete.
     * @return DFA start states that are equivalent to the given NFA start states.  This will have the same length as nfaStartStates, with
     *         corresponding start states in corresponding positions.
     */
    @SuppressWarnings("unchecked")
    public static <MR> List<DfaState<MR>> buildFromNfa(Nfa<MR> nfa, int[] nfaStartStates, DfaAmbiguityResolver<? super MR> ambiguityResolver, BuilderCache cache )
    {
        String cacheKey = null;
        SerializableDfa<MR> serializableDfa = null;
        if (cache != null)
        {
            try
            {
                //generate the cache key by serializing key info into an SHA hash
                SHAOutputStream sha = new SHAOutputStream();
                sha.on(false);
                ObjectOutputStream os = new ObjectOutputStream(sha);
                os.flush();
                sha.on(true);
                os.writeObject(nfaStartStates);
                os.writeObject(nfa);
                os.writeObject(ambiguityResolver);
                os.flush();
                
                cacheKey = sha.getBase32Digest();
                os.close();
            }
            catch(IOException e)
            {
                //doesn't really happen
                throw new RuntimeException(e);
            }
            serializableDfa = (SerializableDfa<MR>)cache.getCachedItem(cacheKey);
        }
        if (serializableDfa == null)
        {
            RawDfa<MR> minimalDfa;
            {
                RawDfa<MR> rawDfa = (new DfaFromNfa<MR>(nfa, nfaStartStates, ambiguityResolver)).getDfa();
                minimalDfa = (new DfaMinimizer<MR>(rawDfa)).getMinimizedDfa();
            }
            serializableDfa = new SerializableDfa<>(minimalDfa);
            if (cacheKey != null && cache != null)
            {
                cache.maybeCacheItem(cacheKey, serializableDfa);
            }
        }
        return serializableDfa.getStartStates();
    }

    private String _getCacheKey(final int dfaType, List<Set<MATCHRESULT>> languages, DfaAmbiguityResolver<? super MATCHRESULT> ambiguityResolver)
    {
        String cacheKey;
        try
        {
            //generate the cache key by serializing key info into an SHA hash
            SHAOutputStream sha = new SHAOutputStream();
            sha.on(false);
            ObjectOutputStream os = new ObjectOutputStream(sha);
            os.flush();
            sha.on(true);
            os.writeInt(dfaType);
            final int numLangs = languages.size();
            os.writeInt(numLangs);
            
            //write key stuff out in an order based on our LinkedHashMap, for deterministic serialization
            for (Entry<MATCHRESULT, List<Matchable>> patEntry : m_patterns.entrySet())
            {
                boolean included = false;
                List<Matchable> patList = patEntry.getValue();
                if (patList.isEmpty())
                {
                    continue;
                }
                for (int i=0; i<numLangs; ++i)
                {
                    if (!languages.get(i).contains(patEntry.getKey()))
                    {
                        continue;
                    }
                    included = true;
                    break;
                }
                if (!included)
                {
                    continue;
                }
                os.writeInt(patList.size());
                if (numLangs>1)
                {
                    int bits=languages.get(0).contains(patEntry.getKey()) ? 1:0;
                    for (int i=1; i<languages.size(); ++i)
                    {
                        if ((i&31)==0)
                        {
                            os.writeInt(bits);
                            bits=0;
                        }
                        if (languages.get(i).contains(patEntry.getKey()))
                        {
                            bits |= 1<<(i&31);
                        }
                    }
                    os.writeInt(bits);
                }
                for (Matchable pat : patList)
                {
                    os.writeObject(pat);
                }
                os.writeObject(patEntry.getKey());
            }
            os.writeInt(0); //0-size pattern list terminates pattern map
            os.writeObject(ambiguityResolver);
            os.flush();
            
            cacheKey = sha.getBase32Digest();
            os.close();
        }
        catch(IOException e)
        {
            //doesn't really happen
            throw new RuntimeException(e);
        }
        return cacheKey;
    }
    
	private SerializableDfa<MATCHRESULT> _build(List<Set<MATCHRESULT>> languages, DfaAmbiguityResolver<? super MATCHRESULT> ambiguityResolver)
	{
		Nfa<MATCHRESULT> nfa = new Nfa<>();
		
		int[] nfaStartStates = new int[languages.size()];
		for (int i=0; i<languages.size(); ++i)
		{
			nfaStartStates[i] = nfa.addState(null);
		}
		
		if (ambiguityResolver == null)
		{
			ambiguityResolver = conflicts -> defaultAmbiguityResolver(conflicts);
		}
		
		for (Entry<MATCHRESULT, List<Matchable>> patEntry : m_patterns.entrySet())
		{
			List<Matchable> patList = patEntry.getValue();
			if (patList == null || patList.size()<1)
			{
				continue;
			}
			int matchState = -1; //start state for matching this token
			for (int i=0; i<languages.size(); ++i)
			{
				if (!languages.get(i).contains(patEntry.getKey()))
				{
					continue;
				}
				if (matchState<0)
				{
					int acceptState = nfa.addState(patEntry.getKey()); //final state accepting this token
					if (patList.size()>1)
					{
					    //we have multiple patterns.  Make a union
						matchState = nfa.addState(null);
						for (Matchable pat : patList)
						{
							nfa.addEpsilon(matchState, pat.addToNFA(nfa, acceptState));
						}
					}
					else
					{
					    //only one pattern no union necessary
						matchState = patList.get(0).addToNFA(nfa, acceptState);
					}
				}
				//language i matches these patterns
				nfa.addEpsilon(nfaStartStates[i],matchState);
			}
		}
		
		SerializableDfa<MATCHRESULT> serializableDfa;
		{
			RawDfa<MATCHRESULT> minimalDfa;
			{
				RawDfa<MATCHRESULT> rawDfa = (new DfaFromNfa<MATCHRESULT>(nfa, nfaStartStates, ambiguityResolver)).getDfa();
				minimalDfa = (new DfaMinimizer<MATCHRESULT>(rawDfa)).getMinimizedDfa();
			}
			serializableDfa = new SerializableDfa<>(minimalDfa);
		}
		return serializableDfa;
	}
	
    private SerializableDfa<Boolean> _buildReverseFinders(List<Set<MATCHRESULT>> languages)
    {
        Nfa<Boolean> nfa = new Nfa<>();
        
        int startState = nfa.addState(null);
        final int endState = nfa.addState(true);
        final DfaAmbiguityResolver<Boolean> ambiguityResolver = conflicts -> defaultAmbiguityResolver(conflicts);

        //First, make an NFA that matches the reverse of all the patterns
        for (Entry<MATCHRESULT, List<Matchable>> patEntry : m_patterns.entrySet())
        {
            List<Matchable> patList = patEntry.getValue();
            if (patList == null || patList.size()<1)
            {
                continue;
            }
            for (int i=0; i<languages.size(); ++i)
            {
                if (!languages.get(i).contains(patEntry.getKey()))
                {
                    continue;
                }
                for (Matchable pat : patEntry.getValue())
                {
                    int st = pat.getReversed().addToNFA(nfa, endState);
                    nfa.addEpsilon(startState, st);
                }
            }
        }
        //omit the empty string
        startState = nfa.Disemptify(startState);
        
        //allow anything first
        startState = Pattern.maybeRepeat(CharRange.ALL).addToNFA(nfa, startState);
        
        //build the DFA
        SerializableDfa<Boolean> serializableDfa;
        {
            RawDfa<Boolean> minimalDfa;
            {
                RawDfa<Boolean> rawDfa = (new DfaFromNfa<Boolean>(nfa, new int[] {startState}, ambiguityResolver)).getDfa();
                minimalDfa = (new DfaMinimizer<Boolean>(rawDfa)).getMinimizedDfa();
            }
            serializableDfa = new SerializableDfa<>(minimalDfa);
        }
        return serializableDfa;
    }
    
    private static <T> T defaultAmbiguityResolver(Set<T> matches)
	{
        throw new DfaAmbiguityException(matches);
	}
}
