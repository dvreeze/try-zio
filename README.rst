=======
Try-ZIO
=======

This project is about getting to know `ZIO`_ better (learning by doing).

The ideas behind ZIO are well explained in `this overview of the background of ZIO`_. Central is
the (FP) idea of exclusively using **pure functions** ("*DTP*": deterministic, total, pure). Counterexamples
are functions the outputs of which partly depend on the current time (not deterministic), the List
function that takes the head of the List (not total), and functions that depend on global state not passed
as parameters (not pure). Also central is the idea of **functional effects**, which are immutable data
structures modelling procedural effects (*programs as values*). Programs then combine functional effects,
and only "at the end" the resulting functional effect is run. These (functional) programs can therefore
be reasoned about very well, limiting the side-effects to the code that actually hits the "run button"
on the combined functional effect.

Functional effects are also known as *IO monads*. The latter term indicates that functional effects
can be combined into larger functional effects using functions like *map* and *flatMap*. In typical
Scala code this means the abundant use of for-comprehensions.

ZIO ships with a *ZIO runtime* (the "interpreter" of ZIO functional effects) that is known to
perform very well, which certainly helped ZIO gain traction.

But why do we need ZIO? Of course, it is very powerful to be able to specify that some functional effect
should be retried several times if needed, or that it can be cancelled or time-out, and that the ZIO runtime
translates that to correct and efficient side-effects. Clearly this FP style of programming can lead to
understandable, testable code. But we have done without functional effect systems for a very long time.
I think that Daniel Spiewak makes a very strong case for functional effect systems in `this talk`_.

There are many functional effect systems around, but ZIO aims to be quite accessible to programmers
not coming from a functional programming background. See for example `this article about ZIO`_.
Knowledge about ZIO does help a lot when dealing with code bases that use other functional effect
systems like `Monix`_ (Task) or `Cats Effect`_.

A nice `ZIO cheat sheet`_ exists for ZIO 1.X, and hopefully soon there will be one for ZIO 2.X.

For newcomers to functional effect systems it may take some getting used to the "mental model"
of composing "*lazy effects*" that are only run at the end when the "run button" is hit. Not
surprisingly, there are some `pitfalls`_ to avoid (and many of them are not specific to ZIO).

In most real-world cases effect systems like ZIO are used in combination with "legacy" systems
such as databases. That often means the use of "blocking" libraries, thus limiting the use of
asynchronous processing. In more extreme cases, effect systems like ZIO are used in combination
with "legacy" APIs like the Java servlet API. It may be a strange combination (even more so with
the existence of the zio-http library), and far from optimal, but it is nevertheless explored below
because it does occur in real world projects.

The unlikely combination of ZIO and servlets
============================================

Combining ZIO with the `Servlet API`_ is not a natural match. For example:

* ZIO is a modern (Scala) API, whereas the (Java) Servlet API is quite old, but a given when using Tomcat or Jetty
* ZIO is used to functionally combine *lazy effects* without running them (only at the end), while the Servlet API is all about side-effects
* ZIO is used to combine (programs as) immutable data structures, whereas the Servlet API mainly offers highly mutable data structures
* ZIO makes it easy to create thread-safe programs (e.g. avoiding memory visibility problems), unlike the Servlet API, if a request happens to be handled in more than 1 thread
* ZIO is flexible in combining "blocking" and asynchronous code, while the Servlet API is quite rigid in how request handling threads are used
* The ZIO runtime uses *green threads*, called *fibers*, which are far more lightweight than Java's OS level threads that are used directly by servlets
* ZIO, especially when combined with zio-http, can be used to create quite lightweight HTTP server functionality, while the Servlet API more or less requires the use of WAR files

Given that such combinations still do occur in practice, it might be a good idea to explore that, and to come up with pitfalls
and good practices.

Traditionally the Servlet API, as implemented by web containers such as Tomcat and Jetty, offers one request handling thread
(from a container managed thread pool) per incoming HTTP request. That means that this thread is blocked for the entire
duration of the processing of each request. If we use effect systems like ZIO, with their own thread pools, this is quite
wasteful: threads are relatively scarce resources, and keeping request handling threads blocked while at the same time
using ZIO managed threads keeps the container managed request handling threads from doing more useful work like handling
other HTTP requests.

Fortunately relatively new versions of the Servlet API offer asynchronous request handling, through *ServletRequest.startAsync.start*.
The *Runnable* passed to this "start" method runs in a different thread than the one where the processing of the request started,
but it is still a thread from the container managed thread pool. The good thing is that the original thread that started handling
of the request is no longer blocked, and is free to start handling other HTTP requests. This is definitely needed if we combine
the Servlet model with ZIO (or another functional effect system).

This gets us to the following flow for handling a servlet request:

* The initial request handling request does little (other than "safely publishing" data needed by other threads), then starts async processing
* The async request handling thread does little (other than "safely publishing" data needed by other threads), then calls a ZIO Runtime method to run the actual ZIO request handling effect
* A ZIO thread pool is used to actually run the request handling ZIO functional effect
* There may be an additional thread (pool) to write the result to the response (to keep the response and response writer out of the ZIO effect)

This is indeed asynchronous request processing, keeping no thread blocked after it has passed its data to the following step in the flow.

Note that "safely publishing" data needed by other threads is important, in order to prevent memory visibility issues (and the corresponding
data corruption issues). It basically means that data is "safely published" to other threads if it is guaranteed that this data
lives in main memory on the exchange instead of in memory caches. The "synchronisation tools" to do so in a program are described
by the `Java memory model`_.

It must be said that there are real costs with using several threads per HTTP request (in a safe way), due to the costs of
synchronising memory caches with main memory. A full ZIO solution using zio-http is at least on paper more efficient than
the processing flow described here, and it would certainly be more natural and less clumsy and error-prone.

Let's describe each of the steps mentioned above in somewhat more detail.

The first step can be characterized as follows:

* The initial request handling thread comes from the container managed thread pool
* It can be used to prepare some (immutable?) data, to be "safely published" for use in other threads
* It then starts async processing, as per the Servlet specification
* The code for this step is written with the "mental model" of regular sequential side-effecting Scala code

The second step is characterized as follows:

* The async second request handling thread also comes from the container managed thread pool
* It can safely obtain servlet request and response objects (through the *AsyncContext* API), and safely publish them for use later on in other threads
* It then calls on the ZIO runtime to (asynchronously) run the *ZIO request handling functional effect* (see below), say, as a Scala Future
* The code for this step is also written with the "mental model" of regular sequential side-effecting Scala code (except for the Future)

The third step is characterized as follows:

* It is a ZIO managed thread pool running the functional effect that describes all the real work done for handling the request
* The bulk of the request handling code is about composing that functional effect, which is run in this step
* This functional effect may be parameterized with data prepared in a previous step (and published safely)
* The code assembling this functional effect is written with the "mental model" of combining "lazy effects", without running anything
* The programmer has control over blocking versus asynchronous behaviour for parts of the functional effect (e.g. blocking for JDBC or where ThreadLocal is used under the hood)
* Related: the programmer has control over ZIO managed timeouts, cancellability etc.

The fourth step, if any, is characterized as follows:

* Let's say that writing the effect's result to the response writer is a Scala Future, then there is yet another thread (pool) involved
* Then this Future can be used/introduced by "flatMapping" on the earlier-mentioned Future (that ran the overall effect)
* Again, earlier-mentioned safely published data can be used (such as the response and response writer)
* The code for this step is written with the "mental model" of writing Scala Futures; they are not lazy behaviour, but they run asynchronously
* Indeed, there is no reason to do a blocking wait on the result of the Scala Future; just complete the request handling asynchronously at the end in the Future

To "publish data" safely in order to prevent memory visibility problems one tool that can be used is Java *AtomicReference*,
for its "volatile" semantics as per the Java memory model.

The above is reasonably complicated, but what have we achieved (using an unnatural "stack")? At least the following:

* Asynchronous request handling, exploiting async support in the Servlet model
* The use of ZIO functional effects for maximum control over the actual work done during request handling, exploiting the safety and testability of FP
* Prevention of memory visibility problems across threads involved in handling of one request

This project contains client and server code that shows all this in action.

Of course I would rather use ZIO with zio-http instead.

.. _`ZIO`: https://zio.dev/
.. _`this overview of the background of ZIO`: https://zio.dev/next/overview/overview_background
.. _`this talk`: https://www.youtube.com/watch?v=qgfCmQ-2tW0
.. _`this article about ZIO`: https://degoes.net/articles/zio-environment
.. _`Monix`: https://monix.io/
.. _`ZIO cheat sheet`: https://github.com/ghostdogpr/zio-cheatsheet
.. _`Cats Effect`: https://typelevel.org/cats-effect/
.. _`pitfalls`: https://medium.com/wix-engineering/5-pitfalls-to-avoid-when-starting-to-work-with-zio-adefdc7d2d5c
.. _`Servlet API`: https://docs.oracle.com/javaee/7/api/javax/servlet/Servlet.html
.. _`Java memory model`: https://www.cs.rice.edu/~johnmc/comp522/lecture-notes/COMP522-2019-Java-Memory-Model.pdf
