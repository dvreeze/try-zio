=======
Try-ZIO
=======

Setup
=====

This project is about learning `ZIO`_ by doing. It also uses other dependencies, such as a Wordpress
database, `jOOQ`_ etc.

Before running console programs and tests, and even before compiling, a few steps are needed.
The idea is to first start a MySQL Docker container, do a "docker exec" into it, and then use the mysql
client to create and fill the Wordpress database:

* Navigate to a directory where the data should be stored, and create datadir and shared sub-directories
* Copy the datadump/wordpress-dump.sql file to the shared sub-directory

Next run some Docker commands:

* sudo docker run --name mysql-wordpress -e MYSQL_ROOT_PASSWORD=root -d -p 3306:3306 -v $PWD/datadir:/var/lib/mysql -v $PWD/shared:/shared mysql:latest
* sudo docker exec -it mysql-wordpress bash

Inside that bash session, enter the following commands (entering the password when prompted):

* mysql -p
* create database wordpress; -- only the first time, if the database does not yet exist
* exit -- leaving mysql, but staying inside the Docker container
* mysql -u root -p wordpress < /shared/wordpress-dump.sql
* mysql -p
* show databases;
* use wordpress; -- must exist
* show tables; -- must have tables
* select count(*) from wp_posts; -- must be non-zero
* exit -- leaving mysql, but still inside the Docker container
* exit -- now no longer connected to the running mysql-wordpress container

If the mysql-wordpress container is stopped, it can be started again, of course. If it is removed,
it must be created and started again, yet the database data should still be there.

Database content can be dumped into a dump file (for later imports) in a mysql session as follows:

* mysqldump -u root -p wordpress > /shared/wordpress-dump-2.sql

Next, with the mysql-wordpress Docker container running, start an sbt session in a terminal with the
root of this project as current directory. As part of the build, a jOOQ code generation task will
run automatically. The generated Java source files represent database tables in jOOQ, and are used
in parts of the code base.

Now we are set up to run programs, tests, etc. If we want to run Wordpress as well, against the
running MySQL Docker container, enter the following command (in the same current directory as
where we started MySQL):

* sudo docker run -e WORDPRESS_DB_USER=root -e WORDPRESS_DB_PASSWORD=root --name wordpress --link mysql-wordpress:mysql -p 8080:80 -v "$PWD/html":/var/www/html -d wordpress

Learning ZIO
============

This project is about getting to know `ZIO`_ better (learning by doing).

The ideas behind ZIO are well explained in `this overview of the background of ZIO`_. Central is
the (FP) idea of exclusively using **pure functions** ("*DTP*": deterministic, total, pure). Counterexamples
are functions the outputs of which partly depend on the current time (not deterministic), the *List.head*
function that takes the head of the List (not total), and functions that depend on global state not passed
as parameters (not pure). Also central is the idea of **functional effects**, which are *immutable* data
structures modelling procedural effects (*programs as values*). Programs then combine functional effects,
and only "at the end" the resulting functional effect is run. These (functional) programs can therefore
be reasoned about very well, limiting the side-effects to the code that actually hits the "run button"
on the combined functional effect.

Functional effects are also known as *IO monads*. The latter term indicates that functional effects
can be combined into larger functional effects using functions like *map* and *flatMap*. In typical
Scala code this means the abundant use of for-comprehensions.

ZIO ships with a *ZIO runtime* (the "interpreter" of ZIO functional effects) that is known to
perform very well, which certainly helped ZIO gain traction.

But why do we need ZIO? Of course, it is very powerful to be able to simply specify that some functional effect
should be retried several times if needed, or that it can be cancelled or time-out, and that the ZIO runtime
makes sure that these effects run correctly and efficiently. Clearly this FP style of programming can lead to
understandable, testable code. But we have done without functional effect systems for a very long time.
There is a very good explanation, though, of why we need functional effect systems, and that is `this talk`_ by
Daniel Spiewak.

There are many functional effect systems around, but ZIO aims to be quite accessible to programmers
not coming from a functional programming background. See for example `this article about ZIO`_.
Knowledge about ZIO does help a lot when dealing with code bases that use other functional effect
systems like `Monix`_ (Task) or `Cats Effect`_.

A nice `ZIO cheat sheet`_ exists for ZIO 1.X, and hopefully soon there will be one for ZIO 2.X.

For newcomers to functional effect systems (like me) it may take some getting used to the "mental model"
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
* ZIO is flexible in combining "blocking" and asynchronous code, while the Servlet API is quite rigid in how (container managed) request handling threads are used
* The ZIO runtime uses *green threads*, called *fibers*, which are far more lightweight than Java's OS level threads that are used directly by servlets
* ZIO, especially when combined with zio-http, can be used to create quite lightweight HTTP server functionality, while the Servlet API more or less requires the use of WAR files

Given that such combinations of ZIO (or an alternative) with servlets still do occur in practice, it might be a good idea to explore that, and to come up with pitfalls
and good practices.

Before doing so, let's first take a step back and briefly revisit different strategies of dealing with data in multi-threaded environments.
As we know, the 3 safe choices for "sharing of mutable state" are:

* Do not mutate data
* Do not share data
* Do not share data simultaneously

With "data" we mean "heap data", so Java objects (Java/Scala class instances). With sharing we mean "sharing across threads".

The third choice requires "locking" (synchronisation, in Java using the *synchronized* keyword). This does not scale well, as
we know, so its use should be quite limited in a program.

The second choice is well-known to Java web developers who use the Servlet API. Traditionally the Servlet API, as implemented by
web containers such as Tomcat and Jetty, offers one request handling thread (from a container managed thread pool) per incoming HTTP request.
It was common to keep most in-memory data local to the request handling thread, that is, to keep references to those (heap)
object graphs local to the request handling thread. Such in-memory data would be short-lived, living no longer than the specific
web request. This approach does scale, but requires a conscious effort of not leaking this data to other threads.

Even in Servlet applications shared mutable state cannot always be avoided, whether long-lived data global to the web application
or data limited to one user Session. For "global" data it was obvious that some kind of synchronisation was needed, but for
sessions it was often forgotten. This could manifest itself as hard to debug *memory visibility problems*, due to data living
in memory caches and not being "committed" to main memory, before the other request handling thread read that data.

Such problems can be solved by playing by the rules of the *Java memory model*. For a concise but good explanation of the JMM, see `JSR 133 FAQ`_.
In short, when sharing data between threads, we need "synchronisation mechanisms" such as *final* (Java)/*val* (Scala), *volatile*
or locks (used directly or via higher level standard concurrency APIs), or else all bets are off in terms of data consistency.

Scala made it easier to somewhat forget about the JMM, because *immutability* became the norm. Typical Scala object graphs are
*deeply immutable*, so in Java terms they have only *final* fields (*val* in Scala) all the way down in the object graph.
Collections are also typically the immutable collection variants. Immutability scales well in multi-threaded programs, unlike
"locking".

When we use Scala with the Servlet API (directly or via Scalatra as Scala-friendly Servlet facade), we are back in a world
of mutable data. The Servlet API itself mainly offers mutable classes. So again the JMM becomes important.

As said above, traditionally the Servlet API offers one (container managed) request handling thread per incoming HTTP request.
That means that this thread is blocked for the entire duration of the processing of each request. If we use effect systems like ZIO,
with their own thread pools, this is quite wasteful: threads are relatively scarce resources, and keeping request handling threads
blocked while at the same time using ZIO managed threads keeps the container managed request handling threads from doing more useful
work like handling other HTTP requests.

Fortunately relatively new versions of the Servlet API offer asynchronous request handling, through *ServletRequest.startAsync(req, res).start*.
The *Runnable* passed to this "start" method runs in a different thread than the one where the processing of the request started,
but it is still a thread from the container managed thread pool. The good thing is that the original thread that started handling
of the request is no longer blocked, and is free to start handling other HTTP requests. This is certainly desirable if we combine
the Servlet model with ZIO (or another functional effect system). The idea then is to make request handling as much as possible
asynchronous. Given that mutable Servlet API objects like requests and responses must be "safely published" to other threads,
the JMM (`Java memory model`_) indeed becomes quite important again.

For more information on (asynchronous) servlets, see the `Servlet 3.0 specification`_. For more on best practices w.r.t. preventing
"blocking", see `Best Practice, Do not block threads`_ (for Monix instead of ZIO, although the ideas are portable to ZIO as well).

This gets us to the following potential flow for handling a servlet request:

* The initial request handling request does little (other than "safely publishing" data needed by other threads), then starts async processing
* The async request handling thread does little (other than "safely publishing" data needed by other threads), then calls a ZIO Runtime method to run the actual ZIO request handling effect
* A ZIO thread pool is used to actually run the request handling ZIO functional effect (see below)
* There may be an additional thread (pool) to write the result to the response (to keep the response and response writer out of the ZIO effect)

This is indeed asynchronous request processing, keeping no thread blocked after it has passed its data to the following step in the flow.

Note indeed that "safely publishing" mutable data needed by other threads is important, in order to prevent memory visibility issues (and the
corresponding data corruption issues). It basically means that data is "safely published" to other threads if it is guaranteed that this data
lives in main memory on the exchange instead of in memory caches. Hence the importance of some basic knowledge about the JMM.

It must be said that there seem to be real costs with using several threads per HTTP request (in a safe way), due to the costs of
synchronising memory caches with main memory. A full ZIO solution using zio-http is at least on paper more efficient than
the processing flow described here, and it would certainly be more natural and less clumsy and error-prone.

Let's describe each of the steps mentioned above in somewhat more detail.

The first step can be characterized as follows:

* The initial request handling thread comes from the container managed thread pool
* It can be used to prepare some (immutable?) data, to be "safely published" for use in other threads
* It then starts async processing, as per the Servlet specification
* The code for this step is written with the "mental model" of regular synchronous blocking side-effecting Scala code (see below)

The second step is characterized as follows:

* The async second request handling thread also comes from the container managed thread pool
* It can safely obtain servlet request and response objects (through the *AsyncContext* API), and safely publish them for use later on in other threads
* It then calls on the ZIO runtime to (asynchronously) run the *ZIO request handling functional effect* (see below), say, as a Scala Future
* The code for this step is also written with the "mental model" of regular synchronous blocking side-effecting Scala code (except for the Future)

The third step is characterized as follows:

* It is a ZIO managed thread pool running the functional effect that describes all the real work done for handling the request
* The bulk of the request handling code is about composing that functional effect, which is run in this step
* This functional effect may be parameterized with data prepared in a previous step (and published safely)
* The code assembling this functional effect is written with the "mental model" of combining "lazy effects", without running anything (see below)
* The programmer has control over blocking versus asynchronous behaviour for parts of the functional effect (e.g. blocking for JDBC or where ThreadLocal is used under the hood)
* Related: the programmer has control over ZIO managed timeouts, cancellability etc.

The fourth step, if any, is characterized as follows:

* Let's say that writing the effect's result to the response writer is a Scala Future, then there is yet another thread (pool) involved
* Then this Future can be used/introduced by "flatMapping" on the earlier-mentioned Future (that ran the overall effect)
* Again, earlier-mentioned safely published data can be used (such as the response and response writer)
* The code for this step is written with the "mental model" of writing Scala Futures; they are not lazy behaviour, but they run asynchronously (see below)
* Indeed, there is no reason to do a blocking wait on the result of the Scala Future; just complete the request handling asynchronously at the end in the Future

To "publish data" safely in order to prevent memory visibility problems one tool that can be used is Java *AtomicReference*,
for its "volatile" semantics as per the Java memory model.

The 3 different "mental models" mentioned above are:

* Normal *synchronous*, *blocking* code. In this style each statement immediately does something (*eager evaluation*), they run sequentially after each other (if we ignore the JMM), and there is no intrinsic need to "chain" them using functions like *map* and *flatMap*
* Scala *asynchronous* *Futures*. In other words, "wannabe values". They start immediately (*eagerly starting evaluation*), run asynchronously (so please do not wait for them to finish), and only when chaining them (map/flatMap) they run sequentially after each other
* ZIO (or Monix or Cats Effect, etc.) *functional effects*. In other words, "lazy effects" or "recipes of programs" or "programs as values". They do not run at all when created/composed (*lazy evaluation*). Do not forget to chain them (map/flatMap) or else functional effects will get lost.

Note that code may look quite similar, even if the "mental model" of its "effect" is quite different. Hence the explicit mentioning
of these different ways to interpret code.

The above is reasonably complicated, but what have we achieved (using an unnatural "stack")? At least the following:

* Asynchronous request handling, exploiting async support in the Servlet model
* The use of ZIO functional effects for maximum control over the actual work done during request handling, exploiting the safety and testability of FP
* Prevention of memory visibility problems across threads involved in handling of one request

This project contains client and server code that shows all this in action.

Probably most Scala web projects exploiting the Servlet API do so via the `Scalatra`_ library.
It would therefore be desirable to extend the experiment above to one where Scalatra is used instead
of directly using the Servlet API. This project uses Scala 3 instead of Scala 2.13, however, and even
if Scalatra itself (supporting Scala 2.13, but not yet supporting Scala 3) can in principle be used
from Scala 3 code if we are careful with dependencies, the quite strict type checker of the Scala 3
compiler did not accept the use of ScalatraServlet and FutureSupport as Servlet super-types together.
Hence the absence of an experiment with Scalatra and ZIO combined.

Of course I would rather use ZIO with zio-http instead.

.. _`ZIO`: https://zio.dev/
.. _`jOOQ`: https://www.jooq.org/
.. _`this overview of the background of ZIO`: https://zio.dev/next/overview/overview_background
.. _`this talk`: https://www.youtube.com/watch?v=qgfCmQ-2tW0
.. _`this article about ZIO`: https://degoes.net/articles/zio-environment
.. _`Monix`: https://monix.io/
.. _`ZIO cheat sheet`: https://github.com/ghostdogpr/zio-cheatsheet
.. _`Cats Effect`: https://typelevel.org/cats-effect/
.. _`pitfalls`: https://medium.com/wix-engineering/5-pitfalls-to-avoid-when-starting-to-work-with-zio-adefdc7d2d5c
.. _`Servlet API`: https://docs.oracle.com/javaee/7/api/javax/servlet/Servlet.html
.. _`JSR 133 FAQ`: https://www.cs.umd.edu/~pugh/java/memoryModel/jsr-133-faq.html
.. _`Servlet 3.0 specification`: https://download.oracle.com/otn-pub/jcp/servlet-3.0-fr-eval-oth-JSpec/servlet-3_0-final-spec.pdf?AuthParam=1649020004_9b8b66cbc7374c0e8306cd6aa308d164
.. _`Java memory model`: https://www.cs.rice.edu/~johnmc/comp522/lecture-notes/COMP522-2019-Java-Memory-Model.pdf
.. _`Best Practice, Do not block threads`: https://monix.io/docs/current/best-practices/blocking.html
.. _`Scalatra`: https://scalatra.org/
