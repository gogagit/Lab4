package ru.semenyutin.lab4;

/** Values flow from one typed stage to the next; an error short-circuits the chain. */
public final class Pipeline<I,O> {
    @FunctionalInterface public interface Filter<A,B> { B apply(A input) throws Exception; }
    private final Filter<I,O> filter;
    private Pipeline(Filter<I,O> filter){this.filter=filter;}
    public static <A,B> Pipeline<A,B> first(Filter<A,B> filter){return new Pipeline<>(filter);}
    public <N> Pipeline<I,N> then(Filter<O,N> next){return new Pipeline<>(input->next.apply(filter.apply(input)));}
    public O run(I input)throws Exception{return filter.apply(input);}
}
