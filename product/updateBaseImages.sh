#!/bin/bash -e
#
# script to query latest tags of the FROM repos, and update Dockerfiles using the latest base images
# requires skopeo (for authenticated registry queries) and jq to do json queries
# 
# https://registry.redhat.io is v2 and requires authentication to query, so login in first like this:
# docker login registry.redhat.io -u=USERNAME -p=PASSWORD

if [[ ! -x /usr/bin/skopeo ]]; then 
	echo "This script requires skopeo. Please install it."
	exit 1
fi

if [[ ! -x /usr/bin/jq ]]; then 
	echo "This script requires jq. Please install it."
	exit 1
fi

QUIET=0 	# less output - omit container tag URLs
VERBOSE=0	# more output
WORKDIR=`pwd`
BRANCH=crw-2.0-rhel-8 # not master
DOCKERFILE="Dockerfile" # or "rhel.Dockerfile"
MAXDEPTH=2
docommit=1 # by default DO commit the change and push it
buildCommand="echo" # By default, no build will be triggered when a change occurs; use -c for a container-build (or -s for scratch).

checkrecentupdates () {
	# set +e
	for d in $(find ${WORKDIR} -maxdepth ${MAXDEPTH} -name ${DOCKERFILE} | sort); do
		pushdir=${d%/${DOCKERFILE}}
		pushd ${pushdir} >/dev/null
			last=$(git lg -1 | grep -v days || true)
			if [[ $last = *[$' \t\n\r']* ]]; then 
				echo "[DEBUG] ${pushdir##*/}"
				echo "[DEBUG] $last" | egrep "seconds|minutes" || true
				echo
			fi
		popd >/dev/null
	done
	# set -e
}

usage () {
	echo "Usage:   $0 -b [BRANCH] [-w WORKDIR] [-f DOCKERFILE] [-maxdepth MAXDEPTH]"
	echo "Example: $0 -b crw-2.0-rhel-8 -w $(pwd) -f rhel.Dockerfile -maxdepth 2"
	echo "Options: 
	--no-commit, -n    do not push to BRANCH
	-q, -v             quiet, verbose output
	--help, -h         help
	--check-recent-updates-only   
	                   don't poll for new base images; just report on 
	                   recently changed Dockerfiles in WORKDIR and subdirs
	"
}

if [[ $# -lt 1 ]]; then usage; exit; fi

while [[ "$#" -gt 0 ]]; do
  case $1 in
    '-w') WORKDIR="$2"; shift 1;;
    '-b') BRANCH="$2"; shift 1;;
    '-f') DOCKERFILE="$2"; shift 1;;
    '-maxdepth') MAXDEPTH="$2"; shift 1;;
    '-c') buildCommand="rhpkg container-build"; shift 0;; # NOTE: will trigger a new build for each commit, rather than for each change set (eg., Dockefiles with more than one FROM)
    '-s') buildCommand="rhpkg container-build --scratch"; shift 0;;
    '-n'|'--nocommit') docommit=0; shift 0;;
    '-q') QUIET=1; shift 0;;
    '-v') QUIET=0; VERBOSE=1; shift 0;;
	'--check-recent-updates-only') QUIET=0; VERBOSE=1; checkrecentupdates; shift 0; exit;;
	'--help'|'-h') usage; exit;;
    *) OTHER="${OTHER} $1"; shift 0;; 
  esac
  shift 1
done

# as seen on https://stackoverflow.com/questions/4023830/how-to-compare-two-strings-in-dot-separated-version-format-in-bash
vercomp () {
    if [[ $1 == $2 ]]
    then
        vercomp_return=0; return 0
    fi
    local IFS=.
    local i ver1=($1) ver2=($2)
    # fill empty fields in ver1 with zeros
    for ((i=${#ver1[@]}; i<${#ver2[@]}; i++))
    do
        ver1[i]=0
    done
    for ((i=0; i<${#ver1[@]}; i++))
    do
        if [[ -z ${ver2[i]} ]]
        then
            # fill empty fields in ver2 with zeros
            ver2[i]=0
        fi
        if ((10#${ver1[i]} > 10#${ver2[i]}))
        then
            vercomp_return=1; return 0
        fi
        if ((10#${ver1[i]} < 10#${ver2[i]}))
        then
            vercomp_return=2; return 0
        fi
    done
    vercomp_return=0; return 0
}

testvercomp () {
    vercomp $1 $3
    # echo "[DEBUG] vercomp_return=$vercomp_return"
    case $vercomp_return in
        0) op='=';;
        1) op='>';;
        2) op='<';;
    esac
    if [[ $op != $2 ]]
    then
        testvercomp_return="false"
    else
        testvercomp_return="true"
    fi
}

pushedIn=0
for d in $(find ${WORKDIR} -maxdepth ${MAXDEPTH} -name ${DOCKERFILE} | sort); do
	if [[ -f ${d} ]]; then
		echo ""
		echo "# Checking ${d%/${DOCKERFILE}} ..."
		# pull latest commits
		if [[ -d ${d%%/${DOCKERFILE}} ]]; then pushd ${d%%/${DOCKERFILE}} >/dev/null; pushedIn=1; fi
		if [[ "${d%/${DOCKERFILE}}" == *"-rhel8" ]]; then
			BRANCHUSED=${BRANCH/rhel-7/rhel-8}
		else
			BRANCHUSED=${BRANCH}
		fi
		git branch --set-upstream-to=origin/${BRANCHUSED} ${BRANCHUSED} -q
		git checkout ${BRANCHUSED} -q && git pull -q
		if [[ ${pushedIn} -eq 1 ]]; then popd >/dev/null; pushedIn=0; fi

		QUERY=""
		FROMPREFIX=""
		LATESTTAG=""
		URLs=$(cat $d | grep FROM -B1);
		for URL in $URLs; do
			URL=${URL#registry.access.redhat.com/}
			URL=${URL#registry.redhat.io/}
			if [[ $VERBOSE -eq 1 ]]; then echo "[DEBUG] URL=$URL"; fi
			if [[ $URL == "https"* ]]; then 
				QUERY="$(echo $URL | sed -e "s#.\+\(registry.redhat.io\|registry.access.redhat.com\)/#skopeo inspect docker://registry.redhat.io/#g")"
				if [[ ${QUIET} -eq 0 ]]; then echo "# $QUERY| jq .RepoTags| egrep -v \"\[|\]|latest|-source\"|sed -e 's#.*\"\(.\+\)\",*#- \1#'|sort -V|tail -5"; fi
				FROMPREFIX=$(echo $URL | sed -e "s#.\+registry.access.redhat.com/##g")
				LATESTTAG=$(${QUERY} 2>/dev/null| jq .RepoTags|egrep -v "\[|\]|latest|-source"|sed -e 's#.*\"\(.\+\)\",*#\1#'|sort -V|tail -1)
				LATE_TAGver=${LATESTTAG%%-*} # 1.0
				LATE_TAGrev=${LATESTTAG##*-} # 15.1553789946 or 15
				LATE_TAGrevbase=${LATE_TAGrev%%.*} # 15
				LATE_TAGrevsuf=${LATE_TAGrev##*.} # 1553789946 or 15
				if [[ $VERBOSE -eq 1 ]]; then echo "[DEBUG] LATE_TAGver=$LATE_TAGver; LATE_TAGrev=$LATE_TAGrev; LATE_TAGrevbase=$LATE_TAGrevbase; LATE_TAGrevsuf=$LATE_TAGrevsuf"; fi
				echo "+ ${FROMPREFIX}:${LATESTTAG}" # jboss-eap-7/eap72-openshift:1.0-15
			elif [[ $URL ]] && [[ $URL == "${FROMPREFIX}:"* ]]; then
				if [[ ${LATESTTAG} ]]; then
					# CRW-205 Support using unpublished freshmaker builds
					# Do not replace 1.0-15.1553789946 with "newer" 1.0-15; instead, keep 1.0-15.1553789946 version
					# URL = jboss-eap-7/eap72-openshift:1.0-15.1553789946
					CURR_TAGver=${URL##*:}; CURR_TAGver=${CURR_TAGver%%-*} # 1.0
					CURR_TAGrev=${URL##*-} # 15.1553789946 or 15
					CURR_TAGrevbase=${CURR_TAGrev%%.*} # 15
					CURR_TAGrevsuf=${CURR_TAGrev##*.} # 1553789946 or 15
					if [[ $VERBOSE -eq 1 ]]; then echo "[DEBUG] 
#CURR_TAGver=$CURR_TAGver; CURR_TAGrev=$CURR_TAGrev; CURR_TAGrevbase=$CURR_TAGrevbase; CURR_TAGrevsuf=$CURR_TAGrevsuf
#LATE_TAGver=$LATE_TAGver; LATE_TAGrev=$LATE_TAGrev; LATE_TAGrevbase=$LATE_TAGrevbase; LATE_TAGrevsuf=$LATE_TAGrevsuf"; fi

					if [[ ${LATE_TAGrevsuf} != ${CURR_TAGrevsuf} ]] || [[ "${LATE_TAGver}" != "${CURR_TAGver}" ]] || [[ "${LATE_TAGrevbase}" != "${CURR_TAGrevbase}" ]]; then
						echo "- ${URL}"
					fi
					# TODO: try using testvercomp against the full tag versions w/ suffixes, eg., 8.16.0-0 ">" 8.15.1-1.1554788812
					if [[ "${LATE_TAGver}" != "${CURR_TAGver}" ]] || [[ ${LATE_TAGrevbase} -gt ${CURR_TAGrevbase} ]] || [[ ${LATE_TAGrevsuf} -gt ${CURR_TAGrevsuf} ]]; then
						testvercomp "${LATE_TAGver}" ">" "${CURR_TAGver}"
						if [[ "${testvercomp_return}" == "true" ]] || [[ ${LATE_TAGrevsuf} -ge ${CURR_TAGrevsuf} ]] || [[ ${LATE_TAGrevbase} -gt ${CURR_TAGrevbase} ]]; then # fix the ${DOCKERFILE}
							echo "++ $d "
							sed -i -e "s#${URL}#${FROMPREFIX}:${LATESTTAG}#g" $d

							# commit change and push it
							if [[ -d ${d%%/${DOCKERFILE}} ]]; then pushd ${d%%/${DOCKERFILE}} >/dev/null; pushedIn=1; fi
							if [[ ${docommit} -eq 1 ]]; then 
								git commit -s -m "[base] Update from ${URL} to ${FROMPREFIX}:${LATESTTAG}" ${DOCKERFILE} && git push origin ${BRANCHUSED}
							fi
							if [[ ${buildCommand} != "echo" ]] || [[ $VERBOSE -eq 1 ]]; then echo "# ${buildCommand}"; fi
							${buildCommand} &
							echo
							if [[ ${pushedIn} -eq 1 ]]; then popd >/dev/null; pushedIn=0; fi
							fixedFiles="${fixedFiles} $d"
						else
							echo "# No change applied for ${URL} -> ${LATESTTAG}"
						fi
					fi
				fi
			fi
		done
	fi
done 
sleep 10s & wait

echo ""
if [[ $fixedFiles ]]; then
	echo "[base] Updated:"
	# if WORKSPACE defined, trim that off; if not, just trim /
	for d in $fixedFiles; do echo " ${d#${WORKSPACE}/}"; done
	echo ""
else
	if [[ ${QUIET} -eq 0 ]]; then echo "[base] No Dockerfiles changed - no new base images found."; fi
fi
