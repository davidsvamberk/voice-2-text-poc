"""Original uncertainty-aware alignment, preserved from the preceding experiments."""
def align(ref, hyp):
    rows, cols = len(ref)+1, len(hyp)+1
    costs = [[0]*cols for _ in range(rows)]
    ops = [[None]*cols for _ in range(rows)]
    for i in range(1,rows):
        costs[i][0]=costs[i-1][0]+(ref[i-1] is not None)
        ops[i][0]='mask_delete' if ref[i-1] is None else 'delete'
    for j in range(1,cols):
        costs[0][j]=j
        ops[0][j]='insert'
    for i in range(1,rows):
        for j in range(1,cols):
            same=ref[i-1]==hyp[j-1]
            masked=ref[i-1] is None
            candidates=[(costs[i-1][j-1]+(not (same or masked)),0,'mask' if masked else 'equal' if same else 'substitute'),
                        (costs[i-1][j]+(not masked),1,'mask_delete' if masked else 'delete'),
                        (costs[i][j-1]+1,2,'insert')]
            costs[i][j],_,ops[i][j]=min(candidates)
    aligned=[]
    i,j=len(ref),len(hyp)
    while i or j:
        op=ops[i][j]
        aligned.append({'op':op,'reference':ref[i-1] if i and op!='insert' else None,'hypothesis':hyp[j-1] if j and op not in ['delete','mask_delete'] else None})
        if op=='insert':j-=1
        elif op in ['delete','mask_delete']:i-=1
        else:i-=1;j-=1
    aligned.reverse()
    counts={op:sum(a['op']==op for a in aligned) for op in ['equal','substitute','delete','insert','mask','mask_delete']}
    known=sum(w is not None for w in ref)
    assert counts['equal']+counts['substitute']+counts['delete']==known
    assert counts['equal']+counts['substitute']+counts['insert']+counts['mask']==len(hyp)
    assert costs[-1][-1]==counts['substitute']+counts['delete']+counts['insert']
    return costs[-1][-1], counts, aligned
