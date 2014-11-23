/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2014, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.styling.css.selector;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geotools.util.logging.Logging;

/**
 * A selector identifies which features are going to be matched by a certain feature, possibly
 * including one or more scale ranges in which the rule is valid. A subclass of selectors, known as
 * pseudo-selectors, are used to specify how to fill/stroke the innards of a mark used to depict
 * points, lines and fills.
 * 
 * @author Andrea Aime - GeoSolutions
 * 
 */
public abstract class Selector implements Comparable<Selector> {
    
    static final Logger LOGGER = Logging.getLogger(Selector.class);

    public static final Selector ACCEPT = new Accept();

    public static final Selector REJECT = new Reject();

    public static Selector and(Selector s1, Selector s2) {
        return and(s1, s2, null);
    }

    public static Selector and(Selector s1, Selector s2, Object context) {
        Selector result = andInternal(s1, s2, context);
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("Combined " + s1 + " and " + s2 + " into: " + result);
        }
        return result;
    }

    private static Selector andInternal(Selector s1, Selector s2, Object context) {
        // merge with Accept
        if (s1 instanceof Accept) {
            return s2;
        } else if (s2 instanceof Accept) {
            return s1;
        }
        
        // merge with Negate
        if (s1 instanceof Reject || s2 instanceof Reject) {
            return REJECT;
        }
        
        // if one of the two is an or, we can fold the other into it to preserve
        // a structure with a top-most or
        if(s1 instanceof Or) {
            return foldInOr((Or) s1, s2, context);
        } else if(s2 instanceof Or) {
            return foldInOr((Or) s2, s1, context);
        }

        // ok, we can flatten all the concatenated and nested ands in a single list
        List<Selector> selectors = new ArrayList<>();
        flatten(selectors, s1, And.class);
        flatten(selectors, s2, And.class);

        // map by class, same class selectors can be merged
        Map<Class, List<Selector>> classifieds = mapByClass(selectors);
        
        // simplest scenario, there is a Reject
        if(classifieds.get(Reject.class) != null) {
            return REJECT;
        }

        // get rid of Accept, they are irrelevant
        classifieds.remove(Accept.class);

        // perform combinations for selected types
        List<Class<? extends Selector>> classes = Arrays.asList(TypeName.class, ScaleRange.class,
                Id.class, Data.class, PseudoClass.class);
        for (Class<? extends Selector> clazz : classes) {
            List<Selector> classSelectors = classifieds.get(clazz);
            if (classSelectors == null) {
                continue;
            }
            if(classSelectors.size() > 1) {
                try {
                    Method combineAnd = clazz.getDeclaredMethod("combineAnd", List.class,
                            Object.class);
                    Selector result = (Selector) combineAnd.invoke(null, classSelectors, context);
                    if (result == REJECT) {
                        return REJECT;
                    } else if (result == ACCEPT) {
                        classifieds.remove(clazz);
                    } else if (result instanceof And) {
                        classifieds.put(clazz, new ArrayList<>(((Composite) result).getChildren()));
                    } else {
                        classifieds.put(clazz, Collections.singletonList(result));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        // build the result
        List<Selector> finalList = new ArrayList<>();
        for (Class c : classes) {
            List<Selector> list = classifieds.get(c);
            if (list != null) {
                finalList.addAll(list);
            }
        }
        if (finalList.size() == 0) {
            return ACCEPT;
        } else if (finalList.size() == 1) {
            return finalList.get(0);
        } else {
            return new And(finalList);
        }
    }
    
    private static Selector foldInOr(Or or, Selector anded, Object context) {
        List<Selector> newChildren = new ArrayList<>();
        for (Selector s : or.getChildren()) {
            Selector combined = and(s, anded, context);
            if (combined == ACCEPT) {
                return ACCEPT;
            } else if (combined != REJECT) {
                newChildren.add(combined);
            }
        }

        if (newChildren.size() == 0) {
            return REJECT;
        }
        return new Or(newChildren);
    }

    private static void flatten(List<Selector> selectors, Selector s, Class<? extends Composite> clazz) {
        if (!clazz.isInstance(s)) {
            selectors.add(s);
        } else {
            Composite composite = ((Composite) s);
            for (Selector child : composite.getChildren()) {
                flatten(selectors, child, clazz);
            }
        }

    }

    private static Map<Class, List<Selector>> mapByClass(List<Selector> selectors) {
        Map<Class, List<Selector>> result = new LinkedHashMap<>();

        for (Selector s : selectors) {
            Class<? extends Selector> clazz = s.getClass();
            List<Selector> list = result.get(clazz);
            if (list == null) {
                list = new ArrayList<>();
                result.put(clazz, list);
            }
            list.add(s);
        }

        return result;
    }

    /**
     * Returns the specificity of this selector
     * 
     * @return
     */
    public abstract Specificity getSpecificity();
    
    @Override
    public int compareTo(Selector o) {
        return getSpecificity().compareTo(o.getSpecificity());
    }
    
    public abstract Object accept(SelectorVisitor visitor);

}
