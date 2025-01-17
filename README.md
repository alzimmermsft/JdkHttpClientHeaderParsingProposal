This repository is an JMH application with recommendations on how `Http1HeaderParser` and the corresponding `HttpHeaders` creation
in the JDK could be optimized for better performance. There are two major improvements in the proposal:

1. Optimize the parsing of the header.
2. Improving turning the intermediate `Map<String, List<String>>` into `HttpHeaders`.


## Optimize the parsing of the header

Optimizing the parsing of the header is more complicated, and the code used here doesn't have access to any internal APIs which
may provide better optimization. The changes made are roughly the following:

- Use a single iteration over the `char[]` representing the header name to validate it and ensure it is lowercased.
  - It appears the code in `Http1HeaderParser` performs double duty to support HTTP/2 header requirements where all names are
    lowercased, an additional optimization would be a flag indicating if lowercasing was required to further improve HTTP/1.1 performance.
- Use a single iteration over the `char[]` representing the header value to trim and validate it.

The optimization for header names removes a linear pass over the header name. The previous logic used a loop called `charAt(int)` for
validation, requiring not only a full pass over the header name but all the overhead of `charAt` validation, and a linear pass for
lowercasing the header name.

Header value optimization is lesser as it only has the possibility of one linear pass but could remove additional allocations due to
trim, and continues to remove the `charAt` overhead.

These changes equate to roughly a 10-20% performance improvement over the current design with roughly equivalent allocations
(might have slightly higher allocations). This performance check was based on HTTP/1.1 header rules and doesn't include a flag for
whether the header name should be lowercased.

## Improving `HttpHeaders` creation

`Http1HeaderParser` uses the public API `HttpHeaders.of` to create the `HttpHeaders` instance. This API is public facing so it needs
to perform validation on the `Map<String, List<String>>` passed as it could be invalid for `HttpHeaders`, which is the proper design.

Unfortunately, `Http1HeaderParser` already does all that validation. The following is a list of what `HttpHeaders.of` validates and
why it doesn't make sense for `Http1HeaderParser`'s `Map<String, List<String>>` to have that performed on it.

1. Validate the header name isn't empty after trimming. This isn't necessary as if the header name was empty `Http1HeaderParser` ignores
   it and `trim` will never modify the header name as that's an invalid header character and would've caused an exception during name
   validation.
2. Validate the `List<String>` values aren't null. If a header name is valid in `Http1HeaderParser` that value will always be a non-null List.
3. Filtering values. This is effectively a no-op as the `BiPredicate` passed will always return true.
4. Ensuring there isn't key duplication. From point #1 `trim` doesn't do anything and header names are inserted lowercased so there can never
   be a duplicate key.
5. Deep copying the passed `Map`. This isn't necessary as `Http1HeaderParser` nulls out it's intermediate holder one `HttpHeaders` is created
   meaning modifications can never be made to that `Map` once `HttpHeaders.of` is called.

The only meaningful work done, without changes to `Http1HeaderParser`, is converting `Http1HeaderParser`'s `HashMap<String, List<String>>` to
the `TreeMap` used by `HttpHeaders`. This can be removed by having `Http1HeaderParser` use a `TreeMap` as its intermediate holder. With this
explained and the change from `HashMap` to `TreeMap` there could be a special factory used internally that just wraps the `TreeMap` with
`unmodifiableMap` and stores it as the `HttpHeaders` reference (almost a no-op at this point).

These changes equate to roughly a 20-30% performance improvement with many fewer allocations.

## Recommendations

The recommdation I'd put forward here is having a better `HttpHeaders` creation should be a higher priority as it contains fewer logic changes
and is a smaller change set, but both changes should be target where optimizing header parsing could possibly leverage internal APIs that couldn't
be used in this benchmark.
